---
navigation_title: "Classic"
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-classic-tokenfilter.html
---

# Classic token filter [analysis-classic-tokenfilter]


Performs optional post-processing of terms generated by the [`classic` tokenizer](/reference/text-analysis/analysis-classic-tokenizer.md).

This filter removes the english possessive (`'s`) from the end of words and removes dots from acronyms. It uses Lucene’s [ClassicFilter](https://lucene.apache.org/core/10_0_0/analysis/common/org/apache/lucene/analysis/standard/ClassicFilter.md).

## Example [analysis-classic-tokenfilter-analyze-ex]

The following [analyze API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-analyze) request demonstrates how the classic token filter works.

```console
GET /_analyze
{
  "tokenizer" : "classic",
  "filter" : ["classic"],
  "text" : "The 2 Q.U.I.C.K. Brown-Foxes jumped over the lazy dog's bone."
}
```

The filter produces the following tokens:

```text
[ The, 2, QUICK, Brown, Foxes, jumped, over, the, lazy, dog, bone ]
```


## Add to an analyzer [analysis-classic-tokenfilter-analyzer-ex]

The following [create index API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-indices-create) request uses the classic token filter to configure a new [custom analyzer](docs-content://manage-data/data-store/text-analysis/create-custom-analyzer.md).

```console
PUT /classic_example
{
  "settings": {
    "analysis": {
      "analyzer": {
        "classic_analyzer": {
          "tokenizer": "classic",
          "filter": [ "classic" ]
        }
      }
    }
  }
}
```


