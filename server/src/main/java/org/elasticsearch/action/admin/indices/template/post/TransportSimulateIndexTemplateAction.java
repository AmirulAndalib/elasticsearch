/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.indices.template.post;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.ChannelActionListener;
import org.elasticsearch.action.support.local.TransportLocalProjectMetadataAction;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.AliasMetadata;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamLifecycle;
import org.elasticsearch.cluster.metadata.DataStreamOptions;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.UpdateForV10;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettingProvider;
import org.elasticsearch.index.IndexSettingProviders;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.shard.IndexLongFieldRange;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.cluster.metadata.DataStreamLifecycle.isDataStreamsLifecycleOnlyMode;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findConflictingV1Templates;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findConflictingV2Templates;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findV2Template;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.resolveDataStreamOptions;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.resolveLifecycle;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.resolveSettings;

public class TransportSimulateIndexTemplateAction extends TransportLocalProjectMetadataAction<
    SimulateIndexTemplateRequest,
    SimulateIndexTemplateResponse> {

    private final MetadataIndexTemplateService indexTemplateService;
    private final NamedXContentRegistry xContentRegistry;
    private final IndicesService indicesService;
    private final SystemIndices systemIndices;
    private final Set<IndexSettingProvider> indexSettingProviders;
    private final ClusterSettings clusterSettings;
    private final boolean isDslOnlyMode;

    /**
     * NB prior to 9.0 this was a TransportMasterNodeReadAction so for BwC it must be registered with the TransportService until
     * we no longer need to support calling this action remotely.
     */
    @UpdateForV10(owner = UpdateForV10.Owner.DATA_MANAGEMENT)
    @SuppressWarnings("this-escape")
    @Inject
    public TransportSimulateIndexTemplateAction(
        TransportService transportService,
        ClusterService clusterService,
        MetadataIndexTemplateService indexTemplateService,
        ActionFilters actionFilters,
        NamedXContentRegistry xContentRegistry,
        IndicesService indicesService,
        SystemIndices systemIndices,
        IndexSettingProviders indexSettingProviders,
        ProjectResolver projectResolver
    ) {
        super(
            SimulateIndexTemplateAction.NAME,
            actionFilters,
            transportService.getTaskManager(),
            clusterService,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            projectResolver
        );
        this.indexTemplateService = indexTemplateService;
        this.xContentRegistry = xContentRegistry;
        this.indicesService = indicesService;
        this.systemIndices = systemIndices;
        this.indexSettingProviders = indexSettingProviders.getIndexSettingProviders();
        this.clusterSettings = clusterService.getClusterSettings();
        this.isDslOnlyMode = isDataStreamsLifecycleOnlyMode(clusterService.getSettings());

        transportService.registerRequestHandler(
            actionName,
            executor,
            false,
            true,
            SimulateIndexTemplateRequest::new,
            (request, channel, task) -> executeDirect(task, request, new ChannelActionListener<>(channel))
        );
    }

    @Override
    protected void localClusterStateOperation(
        Task task,
        SimulateIndexTemplateRequest request,
        ProjectState state,
        ActionListener<SimulateIndexTemplateResponse> listener
    ) throws Exception {
        ProjectMetadata projectWithTemplate;
        if (request.getIndexTemplateRequest() != null) {
            // we'll "locally" add the template defined by the user in the cluster state (as if it existed in the system)
            String simulateTemplateToAdd = "simulate_index_template_" + UUIDs.randomBase64UUID().toLowerCase(Locale.ROOT);
            // Perform validation for things like typos in component template names
            MetadataIndexTemplateService.validateV2TemplateRequest(
                state.metadata(),
                simulateTemplateToAdd,
                request.getIndexTemplateRequest().indexTemplate()
            );
            projectWithTemplate = removeExistingAbstractions(
                indexTemplateService.addIndexTemplateV2(
                    state.metadata(),
                    request.getIndexTemplateRequest().create(),
                    simulateTemplateToAdd,
                    request.getIndexTemplateRequest().indexTemplate()
                ),
                request.getIndexName()
            );
        } else {
            projectWithTemplate = removeExistingAbstractions(state.metadata(), request.getIndexName());
        }

        String matchingTemplate = findV2Template(projectWithTemplate, request.getIndexName(), false);
        if (matchingTemplate == null) {
            listener.onResponse(new SimulateIndexTemplateResponse(null, null));
            return;
        }

        final ProjectMetadata tempProjectMetadata = resolveTemporaryState(matchingTemplate, request.getIndexName(), projectWithTemplate);
        ComposableIndexTemplate templateV2 = tempProjectMetadata.templatesV2().get(matchingTemplate);
        assert templateV2 != null : "the matched template must exist";

        final Template template = resolveTemplate(
            matchingTemplate,
            request.getIndexName(),
            projectWithTemplate,
            isDslOnlyMode,
            xContentRegistry,
            indicesService,
            systemIndices,
            indexSettingProviders
        );

        final Map<String, List<String>> overlapping = new HashMap<>();
        overlapping.putAll(findConflictingV1Templates(tempProjectMetadata, matchingTemplate, templateV2.indexPatterns()));
        overlapping.putAll(findConflictingV2Templates(tempProjectMetadata, matchingTemplate, templateV2.indexPatterns()));

        if (request.includeDefaults()) {
            listener.onResponse(
                new SimulateIndexTemplateResponse(
                    template,
                    overlapping,
                    clusterSettings.get(DataStreamLifecycle.CLUSTER_LIFECYCLE_DEFAULT_ROLLOVER_SETTING)
                )
            );
        } else {
            listener.onResponse(new SimulateIndexTemplateResponse(template, overlapping));
        }
    }

    /**
     * Removes the alias, data stream, or existing index from the cluster state if it matches the given index name
     */
    private static ProjectMetadata removeExistingAbstractions(ProjectMetadata project, String indexName) {
        return ProjectMetadata.builder(project).removeDataStream(indexName).removeAllIndices().build();
    }

    @Override
    protected ClusterBlockException checkBlock(SimulateIndexTemplateRequest request, ProjectState state) {
        return state.blocks().globalBlockedException(state.projectId(), ClusterBlockLevel.METADATA_READ);
    }

    /**
     * Return a temporary cluster state with an index that exists using the
     * matched template's settings
     */
    public static ProjectMetadata resolveTemporaryState(
        final String matchingTemplate,
        final String indexName,
        final ProjectMetadata simulatedProject
    ) {
        Settings settings = resolveSettings(simulatedProject, matchingTemplate);

        // create the index with dummy settings in the cluster state so we can parse and validate the aliases
        Settings dummySettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(settings)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
            .build();

        final IndexMetadata indexMetadata = IndexMetadata.builder(indexName)
            .eventIngestedRange(getEventIngestedRange(indexName, simulatedProject))
            .settings(dummySettings)
            .build();
        return ProjectMetadata.builder(simulatedProject).put(indexMetadata, true).build();
    }

    /**
     * Take a template and index name as well as state where the template exists, and return a final
     * {@link Template} that represents all the resolved Settings, Mappings, Aliases and Lifecycle
     */
    public static Template resolveTemplate(
        final String matchingTemplate,
        final String indexName,
        final ProjectMetadata simulatedProject,
        final boolean isDslOnlyMode,
        final NamedXContentRegistry xContentRegistry,
        final IndicesService indicesService,
        final SystemIndices systemIndices,
        Set<IndexSettingProvider> indexSettingProviders
    ) throws Exception {
        Settings templateSettings = resolveSettings(simulatedProject, matchingTemplate);

        List<Map<String, AliasMetadata>> resolvedAliases = MetadataIndexTemplateService.resolveAliases(simulatedProject, matchingTemplate);

        ComposableIndexTemplate template = simulatedProject.templatesV2().get(matchingTemplate);
        // create the index with dummy settings in the cluster state so we can parse and validate the aliases
        Settings.Builder dummySettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID());

        /*
         * If the index name doesn't look like a data stream backing index, then MetadataCreateIndexService.collectV2Mappings() won't
         * include data stream specific mappings in its response.
         */
        String simulatedIndexName = template.getDataStreamTemplate() != null
            && indexName.startsWith(DataStream.BACKING_INDEX_PREFIX) == false
                ? DataStream.getDefaultBackingIndexName(indexName, 1)
                : indexName;
        List<CompressedXContent> mappings = MetadataCreateIndexService.collectV2Mappings(
            null, // empty request mapping as the user can't specify any explicit mappings via the simulate api
            simulatedProject,
            template,
            xContentRegistry,
            simulatedIndexName
        );

        // First apply settings sourced from index settings providers
        final var now = Instant.now();
        Settings.Builder additionalSettings = Settings.builder();
        Set<String> overrulingSettings = new HashSet<>();
        for (var provider : indexSettingProviders) {
            Settings result = provider.getAdditionalIndexSettings(
                indexName,
                template.getDataStreamTemplate() != null ? indexName : null,
                simulatedProject.retrieveIndexModeFromTemplate(template),
                simulatedProject,
                now,
                templateSettings,
                mappings
            );
            MetadataCreateIndexService.validateAdditionalSettings(provider, result, additionalSettings);
            dummySettings.put(result);
            additionalSettings.put(result);
            if (provider.overrulesTemplateAndRequestSettings()) {
                overrulingSettings.addAll(result.keySet());
            }
        }

        if (overrulingSettings.isEmpty() == false) {
            // Filter any conflicting settings from overruling providers, to avoid overwriting their values from templates.
            final Settings.Builder filtered = Settings.builder().put(templateSettings);
            for (String setting : overrulingSettings) {
                filtered.remove(setting);
            }
            templateSettings = filtered.build();
        }

        // Apply settings resolved from templates.
        dummySettings.put(templateSettings);

        final IndexMetadata indexMetadata = IndexMetadata.builder(indexName)
            .eventIngestedRange(getEventIngestedRange(indexName, simulatedProject))
            .settings(dummySettings)
            .build();

        ProjectMetadata tempProjectMetadata = ProjectMetadata.builder(simulatedProject).put(indexMetadata, true).build();

        List<AliasMetadata> aliases = indicesService.withTempIndexService(
            indexMetadata,
            tempIndexService -> MetadataCreateIndexService.resolveAndValidateAliases(
                indexName,
                Set.of(),
                resolvedAliases,
                tempProjectMetadata,
                xContentRegistry,
                // the context is only used for validation so it's fine to pass fake values for the
                // shard id and the current timestamp
                tempIndexService.newSearchExecutionContext(0, 0, null, () -> 0L, null, emptyMap()),
                IndexService.dateMathExpressionResolverAt(),
                systemIndices::isSystemName
            )
        );

        Map<String, AliasMetadata> aliasesByName = aliases == null
            ? Map.of()
            : aliases.stream().collect(Collectors.toMap(AliasMetadata::getAlias, Function.identity()));

        CompressedXContent mergedMapping = indicesService.<CompressedXContent, Exception>withTempIndexService(
            indexMetadata,
            tempIndexService -> {
                MapperService mapperService = tempIndexService.mapperService();
                mapperService.merge(MapperService.SINGLE_MAPPING_NAME, mappings, MapperService.MergeReason.INDEX_TEMPLATE);

                DocumentMapper documentMapper = mapperService.documentMapper();
                return documentMapper != null ? documentMapper.mappingSource() : null;
            }
        );

        Settings settings = Settings.builder().put(additionalSettings.build()).put(templateSettings).build();
        DataStreamLifecycle.Builder lifecycleBuilder = resolveLifecycle(simulatedProject, matchingTemplate);
        DataStreamLifecycle.Template lifecycle = lifecycleBuilder == null ? null : lifecycleBuilder.buildTemplate();
        if (template.getDataStreamTemplate() != null && lifecycle == null && isDslOnlyMode) {
            lifecycle = DataStreamLifecycle.Template.DATA_DEFAULT;
        }
        DataStreamOptions.Builder optionsBuilder = resolveDataStreamOptions(simulatedProject, matchingTemplate);
        return new Template(
            settings,
            mergedMapping,
            aliasesByName,
            lifecycle,
            optionsBuilder == null ? null : optionsBuilder.buildTemplate()
        );
    }

    private static IndexLongFieldRange getEventIngestedRange(String indexName, ProjectMetadata simulatedProject) {
        final IndexMetadata indexMetadata = simulatedProject.index(indexName);
        return indexMetadata == null ? IndexLongFieldRange.NO_SHARDS : indexMetadata.getEventIngestedRange();
    }
}
