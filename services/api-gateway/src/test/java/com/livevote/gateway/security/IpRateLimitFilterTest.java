package com.livevote.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.livevote.gateway.config.RateLimitProperties;
import com.livevote.gateway.exception.GatewayErrorWriter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IpRateLimitFilterTest {

    private AtomicLong currentNanoTime;
    private IpRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setAuthCapacity(2);
        properties.setAuthWindowSeconds(10);
        properties.setVoteCapacity(3);
        properties.setVoteWindowSeconds(10);
        properties.setMaxTrackedIps(100);

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        currentNanoTime = new AtomicLong(1_000_000_000L);
        filter = new IpRateLimitFilter(properties, new GatewayErrorWriter(mapper), currentNanoTime::get);
    }

    @Test
    void shouldLimitAuthRequestsPerIpAndRefillAfterWindow() {
        AtomicInteger forwardedCount = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            forwardedCount.incrementAndGet();
            return Mono.empty();
        };

        // First 2 requests from 10.0.0.1 pass
        for (int i = 0; i < 2; i++) {
            MockServerWebExchange exchange = postWithIp("/api/auth/login", "10.0.0.1");
            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
        assertThat(forwardedCount.get()).isEqualTo(2);

        // 3rd request from same IP is rejected with 429 + Retry-After
        MockServerWebExchange blocked = postWithIp("/api/auth/login", "10.0.0.1");
        StepVerifier.create(filter.filter(blocked, chain)).verifyComplete();
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(forwardedCount.get()).isEqualTo(2);

        // Different IP is unaffected
        MockServerWebExchange otherIp = postWithIp("/api/auth/login", "10.0.0.2");
        StepVerifier.create(filter.filter(otherIp, chain)).verifyComplete();
        assertThat(forwardedCount.get()).isEqualTo(3);

        // Advance time by 10 seconds -> bucket refills
        currentNanoTime.addAndGet(10_000_000_000L);
        MockServerWebExchange refilled = postWithIp("/api/auth/login", "10.0.0.1");
        StepVerifier.create(filter.filter(refilled, chain)).verifyComplete();
        assertThat(forwardedCount.get()).isEqualTo(4);
    }

    @Test
    void shouldRateLimitVoteEndpointAndSkipReadRoutes() {
        AtomicInteger forwardedCount = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            forwardedCount.incrementAndGet();
            return Mono.empty();
        };

        for (int i = 0; i < 3; i++) {
            MockServerWebExchange voteExchange = postWithIp("/api/polls/poll-1/votes", "192.168.1.50");
            StepVerifier.create(filter.filter(voteExchange, chain)).verifyComplete();
        }
        MockServerWebExchange fourthVote = postWithIp("/api/polls/poll-1/votes", "192.168.1.50");
        StepVerifier.create(filter.filter(fourthVote, chain)).verifyComplete();
        assertThat(fourthVote.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Non-limited GET route passes cleanly
        for (int i = 0; i < 5; i++) {
            MockServerWebExchange readExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/results/poll-1")
                            .header("X-Forwarded-For", "192.168.1.50")
                            .build()
            );
            StepVerifier.create(filter.filter(readExchange, chain)).verifyComplete();
            assertThat(readExchange.getResponse().getStatusCode()).isNull();
        }
        assertThat(forwardedCount.get()).isEqualTo(8);
    }

    private MockServerWebExchange postWithIp(String path, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .header("X-Forwarded-For", ip)
                        .build()
        );
    }
}
