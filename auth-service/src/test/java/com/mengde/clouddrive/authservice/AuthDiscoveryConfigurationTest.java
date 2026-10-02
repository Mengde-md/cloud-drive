package com.mengde.clouddrive.authservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthDiscoveryConfigurationTest {
    @Test
    void defaultAdvertisedAddressMatchesLoopbackListener() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var base = loader.load("base", new FileSystemResource("src/main/resources/application.yml")).get(0);
        var nacos = loader.load("nacos", new FileSystemResource("src/main/resources/application-nacos.yml")).get(0);
        assertThat(base.getProperty("server.address"))
                .isEqualTo("${CLOUDDRIVE_AUTH_BIND_ADDRESS:127.0.0.1}");
        assertThat(nacos.getProperty("spring.cloud.nacos.discovery.ip"))
                .isEqualTo("${CLOUDDRIVE_AUTH_ADVERTISED_IP:127.0.0.1}");
    }
}
