package com.mengde.clouddrive.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class ElasticFileSearchIndexTest {
    HttpServer server;
    RestClientTransport transport;
    ElasticFileSearchIndex index;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile String response = "{}";
    volatile int manifestStatus = 200;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            requests.add(method + " " + path + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (method.equals("HEAD")) exchange.sendResponseHeaders(path.contains("_doc") ? manifestStatus : 200, -1);
            else {
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        transport = new SearchConfiguration().searchTransport("http://127.0.0.1:" + server.getAddress().getPort());
        index = new ElasticFileSearchIndex(new ElasticsearchClient(transport), true, "test");
    }
    @AfterEach void stop() throws Exception {
        if (transport != null) transport.close();
        if (server != null) server.stop(0);
    }
    @Test void bulkHttp200WithItemErrorNeverWritesManifest() {
        response = """
                {"errors":true,"took":1,"items":[{"index":{"_index":"test","_id":"1",
                "status":400,"error":{"type":"mapper_parsing_exception","reason":"fixture"}}}]}
                """;
        assertThatThrownBy(() -> index.replace(9, "g1", List.of(new SearchDocument(1, 9, null, "report", 7, null, "DOCUMENT"))))
                .hasMessage("INDEX_WRITE_FAILED");
        assertThat(requests).anyMatch(r -> r.contains("_bulk") && r.contains("9:g1:1"));
        assertThat(requests).noneMatch(r -> r.startsWith("PUT"));
    }
    @Test void missingManifestIsNotAnEmptySuccessfulSearch() {
        manifestStatus = 404;
        assertThatThrownBy(() -> index.search(9, "g1", new SearchQuery("report", 0, 20))).hasMessage("INDEX_MISSING");
        assertThat(requests).noneMatch(r -> r.contains("_search"));
    }
    @Test void timedOutSearchIsNotAcceptedAsComplete() {
        response = searchResponse(true, 0);
        assertThatThrownBy(() -> index.search(9, "g1", new SearchQuery("report", 0, 20))).hasMessage("ES_PARTIAL_RESULT");
    }
    @Test void failedShardsAreNotAcceptedAsComplete() {
        response = searchResponse(false, 1);
        assertThatThrownBy(() -> index.search(9, "g1", new SearchQuery("report", 0, 20))).hasMessage("ES_PARTIAL_RESULT");
    }
    @Test void requestHasOwnerGenerationDocumentFiltersAndStableSort() {
        response = searchResponse(false, 0);
        assertThat(index.search(9, "g1", new SearchQuery("report", 1, 20)).total()).isZero();
        assertThat(requests).anySatisfy(r -> assertThat(r).contains("_search", "ownerId", "generation", "DOCUMENT", "fileId", "desc", "\"from\":20"));
        assertThat(requests.stream().filter(r -> r.startsWith("HEAD") && r.contains("_doc")).count()).isEqualTo(2);
    }
    @Test void disabledIndexPerformsNoNetworkIo() {
        var disabled = new ElasticFileSearchIndex(new ElasticsearchClient(transport), false, "test");
        assertThatThrownBy(() -> disabled.replace(9, "g1", List.of())).hasMessage("SEARCH_DISABLED");
        assertThat(requests).isEmpty();
    }
    private static String searchResponse(boolean timeout, int failed) {
        return "{\"took\":1,\"timed_out\":" + timeout + ",\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":" + failed
                + "},\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"max_score\":null,\"hits\":[]}}";
    }
}
