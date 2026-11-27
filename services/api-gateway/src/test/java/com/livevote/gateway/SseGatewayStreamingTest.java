package com.livevote.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.livevote.gateway.security.IdentityHeaderFilter;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.gateway.httpclient.response-timeout=400ms",
                "app.jwt.secret=test-jwt-secret-with-at-least-32-bytes-length"
        }
)
class SseGatewayStreamingTest {

    private static final AtomicReference<String> RECEIVED_USER_ID = new AtomicReference<>();
    private static DisposableServer upstreamServer;

    @BeforeAll
    static void startUpstream() {
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes
                        .get("/api/results/{id}/stream", (req, res) -> {
                            RECEIVED_USER_ID.set(req.requestHeaders().get(IdentityHeaderFilter.HEADER_USER_ID));
                            Flux<String> frames = Flux.interval(Duration.ZERO, Duration.ofMillis(650))
                                    .take(3)
                                    .map(index -> {
                                        if (index < 2) {
                                            return "event:results\ndata:{\"totalVotes\":" + index + "}\n\n";
                                        }
                                        return "event:heartbeat\ndata:{\"timestamp\":\"2026-11-27T16:40:00Z\"}\n\n";
                                    });
                            return res.sse()
                                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                                    .header("X-Accel-Buffering", "no")
                                    .sendString(frames);
                        })
                        .get("/api/results/slow", (req, res) -> Mono.delay(Duration.ofMillis(1500))
                                .then(res.status(200)
                                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                        .sendString(Mono.just("{\"ok\":true}"))
                                        .then())))
                .bindNow();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstreamServer != null) {
            upstreamServer.disposeNow();
        }
    }

    @DynamicPropertySource
    static void registerUpstreams(DynamicPropertyRegistry registry) {
        registry.add("app.upstream.results-url", () -> "http://127.0.0.1:" + upstreamServer.port());
        registry.add("app.upstream.votes-url", () -> "http://127.0.0.1:" + upstreamServer.port());
    }

    @Autowired
    private WebTestClient gatewayClient;

    @BeforeEach
    void clearCapturedHeaders() {
        RECEIVED_USER_ID.set("UNSET");
    }

    @Test
    void shouldStreamSseEventsPastGlobalResponseTimeoutAndStripUserHeader() {
        WebTestClient streamingClient = gatewayClient.mutate()
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        FluxExchangeResult<ServerSentEvent<String>> result = streamingClient.get()
                .uri("/api/results/{id}/stream", UUID.randomUUID())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(IdentityHeaderFilter.HEADER_USER_ID, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

        StepVerifier.create(result.getResponseBody())
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("results");
                    assertThat(event.data()).contains("\"totalVotes\":0");
                })
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("results");
                    assertThat(event.data()).contains("\"totalVotes\":1");
                })
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("heartbeat");
                    assertThat(event.data()).contains("\"timestamp\"");
                })
                .verifyComplete();

        assertThat(RECEIVED_USER_ID.get()).isNull();
    }

    @Test
    void shouldKeepShortTimeoutAndCircuitBreakerFallbackOnRestResultsRoute() {
        gatewayClient.get()
                .uri("/api/results/slow")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.path").isEqualTo("/api/results/slow");
    }
}
