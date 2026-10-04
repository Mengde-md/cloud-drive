package com.mengde.clouddrive.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SearchConfiguration {
    @Bean(destroyMethod = "close")
    RestClientTransport searchTransport(@Value("${clouddrive.search.uri}") String uri) {
        var client = RestClient.builder(HttpHost.create(uri))
                .setRequestConfigCallback(config -> config.setConnectTimeout(500)
                        .setSocketTimeout(2000).setConnectionRequestTimeout(500)).build();
        return new RestClientTransport(client, new JacksonJsonpMapper());
    }
    @Bean
    ElasticsearchClient searchClient(RestClientTransport transport) { return new ElasticsearchClient(transport); }
}
