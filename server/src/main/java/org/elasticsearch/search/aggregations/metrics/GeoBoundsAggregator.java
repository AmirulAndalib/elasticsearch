/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.aggregations.metrics;

import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.fielddata.FieldData;
import org.elasticsearch.index.fielddata.GeoPointValues;
import org.elasticsearch.index.fielddata.MultiGeoPointValues;
import org.elasticsearch.search.aggregations.AggregationExecutionContext;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.xcontent.ParseField;

import java.io.IOException;
import java.util.Map;

final class GeoBoundsAggregator extends MetricsAggregator {

    static final ParseField WRAP_LONGITUDE_FIELD = new ParseField("wrap_longitude");

    private final ValuesSource.GeoPoint valuesSource;
    private final boolean wrapLongitude;
    DoubleArray tops;
    DoubleArray bottoms;
    DoubleArray posLefts;
    DoubleArray posRights;
    DoubleArray negLefts;
    DoubleArray negRights;

    GeoBoundsAggregator(
        String name,
        AggregationContext context,
        Aggregator parent,
        ValuesSourceConfig valuesSourceConfig,
        boolean wrapLongitude,
        Map<String, Object> metadata
    ) throws IOException {
        super(name, context, parent, metadata);
        assert valuesSourceConfig.hasValues();
        this.valuesSource = (ValuesSource.GeoPoint) valuesSourceConfig.getValuesSource();
        this.wrapLongitude = wrapLongitude;
        tops = bigArrays().newDoubleArray(1, false);
        tops.fill(0, tops.size(), Double.NEGATIVE_INFINITY);
        bottoms = bigArrays().newDoubleArray(1, false);
        bottoms.fill(0, bottoms.size(), Double.POSITIVE_INFINITY);
        posLefts = bigArrays().newDoubleArray(1, false);
        posLefts.fill(0, posLefts.size(), Double.POSITIVE_INFINITY);
        posRights = bigArrays().newDoubleArray(1, false);
        posRights.fill(0, posRights.size(), Double.NEGATIVE_INFINITY);
        negLefts = bigArrays().newDoubleArray(1, false);
        negLefts.fill(0, negLefts.size(), Double.POSITIVE_INFINITY);
        negRights = bigArrays().newDoubleArray(1, false);
        negRights.fill(0, negRights.size(), Double.NEGATIVE_INFINITY);
    }

    @Override
    public LeafBucketCollector getLeafCollector(AggregationExecutionContext aggCtx, LeafBucketCollector sub) {
        final MultiGeoPointValues values = valuesSource.geoPointValues(aggCtx.getLeafReaderContext());
        final GeoPointValues singleton = FieldData.unwrapSingleton(values);
        return singleton != null ? getLeafCollector(singleton, sub) : getLeafCollector(values, sub);
    }

    private LeafBucketCollector getLeafCollector(MultiGeoPointValues values, LeafBucketCollector sub) {
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (values.advanceExact(doc)) {
                    growBucket(bucket);
                    for (int i = 0; i < values.docValueCount(); ++i) {
                        addPoint(values.nextValue(), bucket);
                    }
                }
            }
        };
    }

    private LeafBucketCollector getLeafCollector(GeoPointValues values, LeafBucketCollector sub) {
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (values.advanceExact(doc)) {
                    growBucket(bucket);
                    addPoint(values.pointValue(), bucket);
                }
            }
        };
    }

    private void growBucket(long bucket) {
        if (bucket >= tops.size()) {
            long from = tops.size();
            tops = bigArrays().grow(tops, bucket + 1);
            tops.fill(from, tops.size(), Double.NEGATIVE_INFINITY);
            bottoms = bigArrays().resize(bottoms, tops.size());
            bottoms.fill(from, bottoms.size(), Double.POSITIVE_INFINITY);
            posLefts = bigArrays().resize(posLefts, tops.size());
            posLefts.fill(from, posLefts.size(), Double.POSITIVE_INFINITY);
            posRights = bigArrays().resize(posRights, tops.size());
            posRights.fill(from, posRights.size(), Double.NEGATIVE_INFINITY);
            negLefts = bigArrays().resize(negLefts, tops.size());
            negLefts.fill(from, negLefts.size(), Double.POSITIVE_INFINITY);
            negRights = bigArrays().resize(negRights, tops.size());
            negRights.fill(from, negRights.size(), Double.NEGATIVE_INFINITY);
        }
    }

    private void addPoint(GeoPoint value, long bucket) {
        tops.set(bucket, Math.max(tops.get(bucket), value.lat()));
        bottoms.set(bucket, Math.min(bottoms.get(bucket), value.lat()));
        if (value.lon() >= 0) {
            posLefts.set(bucket, Math.min(posLefts.get(bucket), value.lon()));
            posRights.set(bucket, Math.max(posRights.get(bucket), value.lon()));
        } else {
            negLefts.set(bucket, Math.min(negLefts.get(bucket), value.lon()));
            negRights.set(bucket, Math.max(negRights.get(bucket), value.lon()));
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        if (owningBucketOrdinal >= tops.size()) {
            return buildEmptyAggregation();
        }
        double top = tops.get(owningBucketOrdinal);
        double bottom = bottoms.get(owningBucketOrdinal);
        double posLeft = posLefts.get(owningBucketOrdinal);
        double posRight = posRights.get(owningBucketOrdinal);
        double negLeft = negLefts.get(owningBucketOrdinal);
        double negRight = negRights.get(owningBucketOrdinal);
        return new InternalGeoBounds(name, top, bottom, posLeft, posRight, negLeft, negRight, wrapLongitude, metadata());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return InternalGeoBounds.empty(name, wrapLongitude, metadata());
    }

    @Override
    public void doClose() {
        Releasables.close(tops, bottoms, posLefts, posRights, negLefts, negRights);
    }
}
