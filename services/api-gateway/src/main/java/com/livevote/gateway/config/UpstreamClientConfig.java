package com.livevote.gateway.config;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Reactor Netty DNS round-robin resolution so scaled backend replicas receive balanced traffic.
 */
@Configuration
public class UpstreamClientConfig {

    @Bean
    public HttpClientCustomizer roundRobinDnsHttpClientCustomizer() {
        return httpClient -> httpClient.resolver(spec -> spec
                .roundRobinSelection(true)
                .cacheMaxTimeToLive(Duration.ofSeconds(5)));
    }
}
