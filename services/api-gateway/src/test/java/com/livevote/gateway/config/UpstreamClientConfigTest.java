package com.livevote.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import reactor.netty.http.client.HttpClient;

class UpstreamClientConfigTest {

    private final UpstreamClientConfig config = new UpstreamClientConfig();

    @Test
    void shouldCreateRoundRobinDnsCustomizerAndApplyToHttpClient() {
        HttpClientCustomizer customizer = config.roundRobinDnsHttpClientCustomizer();
        assertThat(customizer).isNotNull();

        HttpClient customized = customizer.customize(HttpClient.create());
        assertThat(customized).isNotNull();
    }
}
