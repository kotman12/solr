# Overview

The purpose of the saved-search module is to store your queries so they can be efficiently matched against published documents.
A typical use for this kind of tool is alerts/notifications, where a user saves a particular query and expects to get notified
when new documents arrive that match their saved criteria. The process of searching for saved queries with a document is also called
"reverse search", which is a term used in the search components of this module. The saved-search module does not provide the notification
mechanism but simply a request/response API which returns the set of saved queries that match a given document. If, in your case, 
a particular document can match potentially a very large number of queries, it may be useful to consider combining this with Solr’s 
streaming expressions API.

# Basic Configuration
You must configure your update processor chain to contain the `SavedSearchUpdateProcessor` which will convert queries to solr documents.
It must be added after the DistributedUpdateProcessor, i.e:

```xml
<updateRequestProcessorChain>
    <processor class="solr.LogUpdateProcessorFactory"/>
    <processor class="solr.DistributedUpdateProcessorFactory"/>
    <processor class="solr.SavedSearchUpdateProcessorFactory"/>
    <processor class="solr.RunUpdateProcessorFactory"/>
</updateRequestProcessorChain>
```

If you do not wish to modify the existing/default update processor chain you can always create a dedicated one for saved search, i.e.:

```xml
<updateRequestProcessorChain name="saved-search">
```

Once the update endpoint is configured, Solr will still need to know how to translate documents to queries. For that you’ll add the
`reverseSearch` component, the recommended configuration is below:

```xml
<searchComponent name="reverseSearch" class="solr.ReverseSearchComponent">
    <str name="presearcherType">MultipassTermFilteredPresearcher</str>
    <bool name="applyFieldNameAlias">true</bool>
    <int name="numberOfPasses">4</int>
</searchComponent>
```
Optional (although assumed for some of the examples), configure a special `/reverseSearch` request handler:

```xml
<requestHandler name="/reverseSearch" class="solr.ReverseSearchHandler"/>
```

Alternatively, you could just update the existing `/select` handler, i.e.:

```xml
<requestHandler name="/select" class="solr.SearchHandler">
    <lst name="defaults">
        <int name="rows">2147483647</int>
    </lst>
    <arr name="last-components">
        <str>reverseSearch</str>
    </arr>
</requestHandler>
```

You’ll want to add a `savedSearchCache` in your `<query>` config to get peak performance from the saved search components since it 
saves you from CPU-intensive query decoding during the reverse search. This cache is technically optional, but you could expect 
very poor performance without it. Disable it only for very good reasons and after thorough testing:

```xml
<cache name=" savedSearchCache"
class="solr.DefaultSavedSearchCache"
async="false"
maxRamMB="500"
regenerator="solr.SavedSearchLatestRegenerator"/>
```

Finally, the saved-search module requires a few fields be added to your `managed-schema.xml`, the recommended configuration is as follows:

```xml
<field name="_query_id_" type="string" indexed="true" stored="false" docValues="true"
useDocValuesAsStored="true"/>
<field name="_cache_id_" type="string" indexed="true" stored="false" docValues="true"
useDocValuesAsStored="true"/>
<field name="_mq_" type="string" indexed="true" stored="false" docValues="true"
useDocValuesAsStored="true"/>
<field name="__anytokenfield" type="text_general" indexed="true" stored="false" docValues="false" multiValued="true"/>
<dynamicField name="_________________________________________________saved_search_alias_*" type="text_general" multiValued="true"/>
```
Now simply run `bin/solr start -e cloud -Dsolr.modules=saved-search` to start solr with saved-search enabled.
# Getting Started
Below is an example of how to index a query:

```bash
curl "http://localhost:8984/solr/mycollection/update" -d '
[
    {
        "id": "1",
        "_mq_": "content_txt:test",
    }
]'
```

The response should be the same as Solr’s regular update API

Below is an example of how to search for matching queries given a document:

```bash
curl "http://localhost:8984/solr/mycollection/reverseSearch" -d '
    {
        "params":{
        "reverseSearchDocuments":[
            {
              "content_txt":"some content with the word test in it."
            }
        ]
    }
}'
```

The response should be the same as Solr’s regular select or query API.

Currently we don’t support retrieving or storing additional
columns alongside each saved search although this should be easy to implement in the future. Storing queries with default fields is
also not supported yet.

Query alerting workflows typically involve fetching all searches that have been saved by users which match a newly published document.
This is quite different from a typical top-N document search in that the result set is technically unbounded. In practice we frequently
find that the number of searches "fetched" by a new document is usually well within modern computer resource limits. But if this is not
the case you can always fall back on Solr’s streaming expressions which should work with the saved-search module. To do this you must
configure the `/select` handler to have a ReverseSearch component as mentioned in Basic Configuration. The StreamHandler delegates to the
`/select` search handler when distributing the stream request and so any dedicated `/reverseSearch` handler you have configured will be ignored.
Below is an example of how to stream a reverse search:

```bash
curl --data-urlencode 'expr=search(mycollection,
fl="_query_id_",
json="{\"params\":{\"reverseSearchDocuments\":[{\"content_txt\":\"some content with the word test in it.\"}]}}",
sort="_query_id_ asc")' http://localhost:8983/solr/mycollection/stream
```

# Advanced Configuration

## Saved Search Schema
The query payload is kept in the reserved `_mq_` field briefly mentioned in Basic Configuration which shows it being stored as a docValue.
Because the `_mq_` field is a string, which has a 32KB size-limit, if you exceed that limit you may see an error along the lines of:

```
Exception writing document id 0 to the index; possible analysis error:
Document contains at least one immense term in field="_mq_" (whose UTF8 encoding is longer than the max length 32766)
```

The benefit of storing queries as docValues is faster disk reads but if you must get around this size limit you can change
the `_mq_` configuration to:

```xml
<field name="_mq_" type="string" indexed="false" stored="true" docValues="false"/>
```
Lucene-monitor doesn’t face this issue since it uses binary docValues but unfortunately Solr’s `BinaryField` doesn’t support docValues yet.

## Saved Search Cache
The lucene-monitor library needs a lucene Query object to run a reverse search. They can be kept in-memory to avoid deserializing
at search-time. Because this deserialization can occur at a large scale, it is quite common to keep these caches quite large. For instance,
lucene-monitor will force you to cache all queries. The saved-search module is more flexible in this regard. You can, for instance, set the
maxSize (or maxRamMB) to something less than your full index size by using this configuration:

```xml
<cache name=" savedSearchCache"
class="solr.DefaultSavedSearchCache "
async="false"
maxSize="10000"/>
```

In this example, the omission of the `SavedSearchLatestRegenerator` is important because you want to avoid eagerly loading the latest entries
to the cache on every new searcher. So if you only want to cache the fraction of queries that trigger the most alerts or notifications you most
likely want to configure the cache without a regenerator to minimize churn. However, if you already use lucene-monitor and are happy with how
it does caching then you’ll want to set this cache size to fit all your searches and enable the regenerator as was shown in Basic Configuration.

This module will technically work without any cache but this is not recommended. Aside from unnecessary query deserialization you will also
be running without a query-term filter. The query-term-filter is another important optimization that can drastically reduce the size of the
pre-search filter (more on this in the next section). If you have a use-case where  you really need to decouple the query-term filter from
the query cache you can raise a PR.

## Presearchers
The saved-search module uses lucene’s monitor library to index queries efficiently. It does this through a two-phase process; a cheap
approximate filter called a "presearcher" followed by an accurate but expensive matching step. The second phase runs the surviving candidate 
queries against an ephemeral index containing only the target document(s). Arguably the most important optimization is extracting relevant 
terms from each query that an incoming document must have to be considered for the final matching step. Therefore, the presearcher configuration
is one of the knobs to look at when tuning saved search performance. You can gauge the performance of your presearcher with the help of the 
`debugQuery=true` parameter. This should return, among other things, a `queriesRun` value which will tell you how many queries passed through 
the pre-search stage. You want to keep this number as low as possible relative to the actual result size.

The simplest available Presearcher is the `TermFilteredPresearcher` which, among other things, extracts terms from the query which it stores in
an  index later read by the presearch filter query. The terms it chooses to index depend on the query and the `TermWeightor` it is configured with. 
The default weightor will pick longer terms over shorter ones, so if you have a query `ruler_txt:lrrr AND planet_txt:"omicron persei 8"` the 
presearcher will choose to index `omicron` since it is longer than any other intersected term. Note that this can obviously be suboptimal if,
for instance, you know that the `planet_txt` field is low cardinality and thus indexing its terms will not a produce a very selective sieve. 
Thus, the saved-search module lets you override the default `TermWeightor` like so:


```xml
<searchComponent name="reverseSearch"
class="org.apache.solr.monitor.search.ReverseSearchComponent">
    <str name="presearcherType">TermFilteredPresearcher</str>
    <str name=" termWeightorType ">com.your.CustomTermWeightor</str>
</searchComponent>
```

Note that any user-defined `TermWeightor` must implement be a zero-argument-constructor implementation of lucene’s `TermWeightor` interface.
The `TermWeightor` interface itself has some factory methods which are a good starting point. They include common strategies you might expect
such as term frequency and field-based weighting. Having said that, it would be nice to have a term-weightor that is more integrated with Solr.
One can imagine a weightor that can be directed at a live document collection and dynamically recalculate its weights based on up-to-date term
frequencies. If this sounds useful and interesting to you, raise a PR!

The second presearcher available out-of-the-box is the `MultipassTermFilteredPresearcher`, which was already introduced in Basic Configuration.
This presearcher is configured with a `numberOfPasses` parameter which is used to decide when to terminate query tree traversal during
query-to-document translations. To explain how it works we will take the example query, `planet_txt:"omicron persei"` and assume
`numberOfPasses=3`. The heaviest term `omicron` will be indexed to a pass-specific field, `planet_txt_0`. The second and third heaviest terms
(last term being repeated) will be indexed as `planet_txt_1:persei` and `planet_txt_2:persei`. Continuing the example, if you have a document with
the field `planet_txt:"omicron ceti"`, the multi-pass presearcher will multiply the disjunction of document terms by the number of passes,
expanding it into the query:

```
(planet_txt_0:omicron OR  planet_txt_0: ceti) AND
(planet_txt_1:omicron OR planet_txt_1: ceti) AND
(planet_txt_2:omicron OR  planet_txt_2: ceti)
```
This will not match our example query and so the expensive/exact second phase is avoided. In short, the `MultipassTermFilteredPresearcher` 
leads to longer queries and larger indexes but is also more selective than a `TermFilteredPresearcher` and thus can lead to less time tuning
term-weights. There is an important optimization here that was glossed over, which applies to any `TermFilteredPresearcher`, but is especially
critical for the multi-pass variety; the first step to building a query from a document is filtering out all terms which are completely absent
from the query index. Thus if the term `planet_txt:ceti` did not appear in any indexed query, then the example document would get expanded to
the much shorter `planet_txt_0:omicron AND planet_txt_1:omicron AND planet_txt_2:omicron`.

Finally, the saved-search module lets you implement your own presearcher if neither of these suits your needs. For instance, you may need to
store a lot of queries with numerical ranges for which lucene-monitor has no good optimizations (yet). Once you develop a presearcher with such
capabilities you should 1) raise a PR to add it to lucene-monitor immediately and 2) configure it as a custom presearcher to hold you over
while you wait for your Lucene and Solr PRs to be merged:

```xml
<searchComponent name="reverseSearch" class="org.apache.solr.monitor.search.ReverseSearchComponent">
    <str name="presearcherType"> org.your.NumericAndTermFilteredPresearcher </str>
</searchComponent>
```
