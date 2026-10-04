package com.mengde.clouddrive.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;

@Component
public class ElasticFileSearchIndex implements FileSearchIndex {
    private final ElasticsearchClient client;
    private final boolean enabled;
    private final String name;

    public ElasticFileSearchIndex(ElasticsearchClient client,
            @Value("${clouddrive.search.enabled:false}") boolean enabled,
            @Value("${clouddrive.search.index:clouddrive-files-v1}") String name) {
        this.client = client;
        this.enabled = enabled;
        this.name = name;
    }

    private void requireEnabled() {
        if (!enabled) throw new SearchUnavailableException("SEARCH_DISABLED");
    }

    private void ensureIndex() throws IOException {
        if (client.indices().exists(r -> r.index(name)).value()) return;
        try {
            client.indices().create(r -> r.index(name).settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m.dynamic(DynamicMapping.Strict)
                            .properties("fileId", p -> p.long_(v -> v))
                            .properties("ownerId", p -> p.long_(v -> v))
                            .properties("parentId", p -> p.long_(v -> v))
                            .properties("byteSize", p -> p.long_(v -> v))
                            .properties("generation", p -> p.keyword(v -> v))
                            .properties("kind", p -> p.keyword(v -> v))
                            .properties("displayName", p -> p.text(v -> v.analyzer("standard")
                                    .fields("exact", f -> f.keyword(k -> k))))));
        } catch (ElasticsearchException race) {
            if (!"resource_already_exists_exception".equals(race.error().type())) throw race;
        }
    }

    @Override public void replace(long owner, String generation, List<SearchDocument> documents) {
        requireEnabled();
        try {
            ensureIndex();
            if (!documents.isEmpty()) {
                var request = new BulkRequest.Builder().refresh(Refresh.WaitFor);
                for (var row : documents) {
                    if (row.ownerId() != owner) throw new IllegalArgumentException("mixed owner snapshot");
                    request.operations(op -> op.index(i -> i.index(name).id(owner + ":" + generation + ":" + row.fileId())
                            .document(row.inGeneration(generation))));
                }
                var response = client.bulk(request.build());
                if (response.errors() || response.items().stream().anyMatch(item -> item.error() != null)) {
                    throw new SearchUnavailableException("INDEX_WRITE_FAILED");
                }
            }
            client.index(r -> r.index(name).id(manifestId(owner, generation)).refresh(Refresh.WaitFor)
                    .document(SearchDocument.manifest(owner, generation)));
        } catch (IOException | ElasticsearchException failure) {
            throw new SearchUnavailableException("ES_UNAVAILABLE", failure);
        }
    }

    @Override public IndexPage search(long owner, String generation, SearchQuery query) {
        requireEnabled();
        try {
            if (!client.exists(r -> r.index(name).id(manifestId(owner, generation))).value()) {
                throw new SearchUnavailableException("INDEX_MISSING");
            }
            var result = client.search(r -> r.index(name).from(query.offset()).size(query.size())
                    .trackTotalHits(t -> t.enabled(true)).sort(s -> s.field(f -> f.field("fileId").order(SortOrder.Desc)))
                    .query(q -> q.bool(b -> b.filter(scope(owner, generation))
                            .filter(f -> f.term(t -> t.field("kind").value("DOCUMENT")))
                            .must(m -> m.match(v -> v.field("displayName").query(query.q()).operator(Operator.And))))), SearchDocument.class);
            if (result.timedOut() || result.shards().failed().intValue() > 0 || result.hits().total() == null) {
                throw new SearchUnavailableException("ES_PARTIAL_RESULT");
            }
            if (!client.exists(r -> r.index(name).id(manifestId(owner, generation))).value()) {
                throw new SearchUnavailableException("INDEX_MISSING");
            }
            var ids = result.hits().hits().stream().map(hit -> {
                var row = hit.source();
                if (row == null || row.ownerId() != owner || !generation.equals(row.generation()) || !"DOCUMENT".equals(row.kind())) {
                    throw new SearchUnavailableException("INDEX_INVALID");
                }
                return row.fileId();
            }).toList();
            return new IndexPage(ids, result.hits().total().value());
        } catch (IOException | ElasticsearchException failure) {
            throw new SearchUnavailableException("ES_UNAVAILABLE", failure);
        }
    }

    @Override public void removeGeneration(long owner, String generation) {
        requireEnabled();
        try {
            client.deleteByQuery(r -> r.index(name).query(q -> q.bool(b -> b.filter(scope(owner, generation)))));
        } catch (IOException | ElasticsearchException failure) {
            throw new SearchUnavailableException("ES_UNAVAILABLE", failure);
        }
    }

    private static List<Query> scope(long owner, String generation) {
        return List.of(Query.of(q -> q.term(t -> t.field("ownerId").value(owner))),
                Query.of(q -> q.term(t -> t.field("generation").value(generation))));
    }
    private static String manifestId(long owner, String generation) { return owner + ":" + generation + ":manifest"; }
}
