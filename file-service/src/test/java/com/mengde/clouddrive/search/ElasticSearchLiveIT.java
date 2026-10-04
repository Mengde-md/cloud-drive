package com.mengde.clouddrive.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.mengde.clouddrive.domain.EntryKind;
import org.junit.jupiter.api.*;
import java.net.ServerSocket;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

// Explicit opt-in: mvn test -Dtest=ElasticSearchLiveIT, with CLOUDDRIVE_SEARCH_TEST_URI set.
class ElasticSearchLiveIT {
    SearchDatabaseFixture db;
    RestClientTransport transport;
    ElasticsearchClient client;
    FileSearchService service;
    String name;
    @BeforeEach void start() throws Exception {
        String uri = System.getenv("CLOUDDRIVE_SEARCH_TEST_URI");
        assertThat(uri).as("A disposable ES 8.10.4 endpoint is required").isNotBlank();
        db = new SearchDatabaseFixture();
        transport = new SearchConfiguration().searchTransport(uri);
        client = new ElasticsearchClient(transport);
        assertThat(client.info().version().number()).isEqualTo("8.10.4");
        name = "day27-it-" + UUID.randomUUID();
        service = new FileSearchService(db.mapper, new ElasticFileSearchIndex(client, true, name), db.manager);
    }
    @AfterEach void close() throws Exception {
        try {
            if (client != null && name != null && client.indices().exists(r -> r.index(name)).value()) {
                client.indices().delete(r -> r.index(name));
            }
        } finally {
            if (transport != null) transport.close();
            if (db != null) db.close();
        }
    }
    @Test void actualMappingOwnerFiltersUnicodeAndDeterministicPagination() throws Exception {
        long first = db.add("Quarterly report alpha");
        long second = db.add("Quarterly report beta");
        db.add("中文检索报告");
        db.add(db.other, "Quarterly report private", EntryKind.DOCUMENT);
        db.add(db.owner, "Quarterly report folder", EntryKind.DIRECTORY);
        var page = service.search(db.owner, new SearchQuery("report", 0, 1));
        assertThat(page.source()).isEqualTo("ELASTICSEARCH");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(FileSearchService.SearchItem::id).containsExactly(second);
        assertThat(service.search(db.owner, new SearchQuery("report", 1, 1)).items())
                .extracting(FileSearchService.SearchItem::id).containsExactly(first);
        assertThat(service.search(db.owner, new SearchQuery("report", 2, 1)).items()).isEmpty();
        assertThat(service.search(db.owner, new SearchQuery("中文", 0, 20)).total()).isEqualTo(1);
        var mapping = client.indices().getMapping(r -> r.index(name)).result().get(name).mappings();
        assertThat(mapping.properties().get("ownerId").isLong()).isTrue();
        assertThat(mapping.properties().get("generation").isKeyword()).isTrue();
        assertThat(mapping.properties().get("displayName").text().analyzer()).isEqualTo("standard");
    }
    @Test void renameDeleteRestoreAndPurgeRebuildVisibleResults() {
        long id = db.add("before report");
        assertThat(service.search(db.owner, new SearchQuery("before", 0, 20)).total()).isEqualTo(1);
        db.tx.executeWithoutResult(s -> db.entries.renameByOwner(db.owner, id, "after report"));
        assertThat(service.search(db.owner, new SearchQuery("before", 0, 20)).total()).isZero();
        assertThat(service.search(db.owner, new SearchQuery("after", 0, 20)).total()).isEqualTo(1);
        db.tx.executeWithoutResult(s -> db.entries.deleteByOwnerAndId(db.owner, id));
        assertThat(service.search(db.owner, new SearchQuery("report", 0, 20)).total()).isZero();
        db.tx.executeWithoutResult(s -> db.entries.restoreByOwnerAndId(db.owner, id));
        assertThat(service.search(db.owner, new SearchQuery("report", 0, 20)).total()).isEqualTo(1);
        db.tx.executeWithoutResult(s -> {
            db.entries.deleteByOwnerAndId(db.owner, id);
            db.entries.purgeTrashByOwner(db.owner);
        });
        var empty = service.search(db.owner, new SearchQuery("report", 0, 20));
        assertThat(empty.total()).isZero();
        assertThat(empty.degraded()).isFalse();
    }
    @Test void deletedIndexFallsBackThenExplicitReindexRestoresIt() throws Exception {
        db.add("report");
        assertThat(service.search(db.owner, new SearchQuery("report", 0, 20)).source()).isEqualTo("ELASTICSEARCH");
        client.indices().delete(r -> r.index(name));
        var fallback = service.search(db.owner, new SearchQuery("report", 0, 20));
        assertThat(fallback.source()).isEqualTo("MYSQL");
        assertThat(fallback.total()).isEqualTo(1);
        assertThat(service.reindex(db.owner).ready()).isTrue();
        assertThat(service.search(db.owner, new SearchQuery("report", 0, 20)).degraded()).isFalse();
    }
    @Test void realConnectionFailureLeavesWritesUsableAndReturnsExplicitDegradation() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try (var unavailable = new SearchConfiguration().searchTransport("http://127.0.0.1:" + port)) {
            var offline = new FileSearchService(db.mapper,
                    new ElasticFileSearchIndex(new ElasticsearchClient(unavailable), true, name), db.manager);
            db.add("report during outage");
            var result = offline.search(db.owner, new SearchQuery("report", 0, 20));
            assertThat(result.source()).isEqualTo("MYSQL");
            assertThat(result.reason()).isEqualTo("ES_UNAVAILABLE");
            assertThat(result.total()).isEqualTo(1);
            assertThatThrownBy(() -> offline.reindex(db.owner)).hasMessage("ES_UNAVAILABLE");
        }
        assertThat(service.search(db.owner, new SearchQuery("report", 0, 20)).source()).isEqualTo("ELASTICSEARCH");
    }
    @Test void incompatibleMappingProducesRealBulkItemFailureWithoutPublishingGeneration() throws Exception {
        client.indices().create(r -> r.index(name).mappings(m -> m.properties("displayName", p -> p.long_(v -> v))));
        db.add("not a number");
        var result = service.search(db.owner, new SearchQuery("number", 0, 20));
        assertThat(result.reason()).isEqualTo("INDEX_WRITE_FAILED");
        assertThat(result.total()).isEqualTo(1);
        assertThat(db.state().indexedRevision()).isEqualTo(-1);
    }
}
