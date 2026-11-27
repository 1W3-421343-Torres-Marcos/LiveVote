package com.livevote.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livevote.gateway.config.TraceIdFilter;
import com.livevote.gateway.security.IdentityHeaderFilter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.jwt.secret=test-jwt-secret-with-at-least-32-bytes-length",
                "app.rate-limit.auth-capacity=3",
                "app.rate-limit.auth-window-seconds=60",
                "app.rate-limit.vote-capacity=5",
                "app.rate-limit.vote-window-seconds=60"
        }
)
@Testcontainers
class ApiGatewayIntegrationTest {

    private static final String TEST_SECRET = "test-jwt-secret-with-at-least-32-bytes-length";

    @Container
    static GenericContainer<?> wireMock = new GenericContainer<>("wiremock/wiremock:3.9.2-1-alpine")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    @DynamicPropertySource
    static void registerUpstreams(DynamicPropertyRegistry registry) {
        registry.add("app.upstream.votes-url", ApiGatewayIntegrationTest::wireMockBaseUrl);
        registry.add("app.upstream.results-url", ApiGatewayIntegrationTest::wireMockBaseUrl);
    }

    private static String wireMockBaseUrl() {
        return "http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(8080);
    }

    @Autowired
    private WebTestClient gatewayClient;

    @Autowired
    private ObjectMapper objectMapper;

    private WebTestClient adminClient;

    @BeforeEach
    void resetWireMock() {
        adminClient = WebTestClient.bindToServer().baseUrl(wireMockBaseUrl()).build();
        adminClient.post().uri("/__admin/mappings/reset").exchange().expectStatus().isOk();
        adminClient.delete().uri("/__admin/requests").exchange().expectStatus().isOk();

        stub("GET", "/api/polls/mine", 200, "[]");
        stub("GET", "/api/polls/public-1", 200, "{\"id\":\"public-1\"}");
        stub("POST", "/api/polls/poll-1/votes", 409, "{\"status\":409,\"message\":\"Already voted\"}");
        stub("POST", "/api/auth/login", 200, "{\"token\":\"ok\"}");
        stub("GET", "/api/results/failing", 500, "{\"error\":\"upstream boom\"}");
    }

    @Test
    void shouldEnforceIdentityHeadersPassDomainErrorsRateLimitAndFallback() throws Exception {
        // 1. Protected route with spoofed X-User-Id and no JWT -> 401 without hitting upstream
        gatewayClient.get()
                .uri("/api/polls/mine")
                .header(IdentityHeaderFilter.HEADER_USER_ID, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.status").isEqualTo(401);

        // 2. Protected route with valid JWT replaces spoofed X-User-Id with verified subject and propagates X-Trace-Id
        UUID realUserId = UUID.randomUUID();
        String jwt = signToken(realUserId);
        gatewayClient.get()
                .uri("/api/polls/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .header(IdentityHeaderFilter.HEADER_USER_ID, UUID.randomUUID().toString())
                .header(TraceIdFilter.HEADER_TRACE_ID, "trace-gw-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(TraceIdFilter.HEADER_TRACE_ID, "trace-gw-123");

        JsonNode mineRequests = fetchWireMockRequests("/api/polls/mine");
        assertThat(mineRequests).hasSize(1);
        assertThat(mineRequests.get(0).get("headers").get("X-User-Id").asText()).isEqualTo(realUserId.toString());
        assertThat(mineRequests.get(0).get("headers").get("X-Trace-Id").asText()).isEqualTo("trace-gw-123");

        // 3. Public route strips spoofed X-User-Id before forwarding
        gatewayClient.get()
                .uri("/api/polls/public-1")
                .header(IdentityHeaderFilter.HEADER_USER_ID, UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk();

        JsonNode publicRequests = fetchWireMockRequests("/api/polls/public-1");
        assertThat(publicRequests).hasSize(1);
        assertThat(publicRequests.get(0).get("headers").has("X-User-Id")).isFalse();

        // 4. Downstream 409 passes through cleanly (does not trigger Circuit Breaker fallback)
        gatewayClient.post()
                .uri("/api/polls/poll-1/votes")
                .header("X-Forwarded-For", "198.51.100.10")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"optionId\":\"11111111-1111-1111-1111-111111111111\",\"deviceId\":\"d1\"}")
                .exchange()
                .expectStatus().isEqualTo(409);

        // 5. Rate limiting on /api/auth/login (capacity = 3): 4th request returns 429 + Retry-After
        for (int i = 0; i < 3; i++) {
            gatewayClient.post()
                    .uri("/api/auth/login")
                    .header("X-Forwarded-For", "203.0.113.77")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"email\":\"a@b.com\",\"password\":\"12345678\"}")
                    .exchange()
                    .expectStatus().isOk();
        }
        gatewayClient.post()
                .uri("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113.77")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.com\",\"password\":\"12345678\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429);

        // 6. Downstream 500 triggers Circuit Breaker fallback returning 503 JSON
        gatewayClient.get()
                .uri("/api/results/failing")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.path").isEqualTo("/api/results/failing");

        // 7. Actuator liveness, readiness, and Golden Signals metrics
        gatewayClient.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .value(body -> assertThat(body).containsEntry("status", "UP"));

        gatewayClient.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .value(body -> {
                    assertThat(body).containsEntry("status", "UP");
                    assertThat(body).containsKey("components");
                });

        gatewayClient.get()
                .uri("/actuator/metrics/http.server.requests")
                .exchange()
                .expectStatus().isOk();

        gatewayClient.get()
                .uri("/actuator/metrics/livevote.gateway.fallback.total")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.measurements[0].value").value(value ->
                        assertThat(((Number) value).doubleValue()).isGreaterThanOrEqualTo(1.0d));
    }

    private void stub(String method, String path, int status, String responseBody) {
        String mapping = """
                {
                  "request": { "method": "%s", "urlPath": "%s" },
                  "response": {
                    "status": %d,
                    "headers": { "Content-Type": "application/json" },
                    "body": %s
                  }
                }
                """.formatted(method, path, status, quoteJson(responseBody));

        adminClient.post()
                .uri("/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapping)
                .exchange()
                .expectStatus().isCreated();
    }

    private String quoteJson(String raw) {
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode fetchWireMockRequests(String urlPath) throws Exception {
        byte[] bytes = adminClient.get()
                .uri("/__admin/requests")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();
        JsonNode root = objectMapper.readTree(bytes);
        var filtered = objectMapper.createArrayNode();
        for (JsonNode entry : root.get("requests")) {
            JsonNode req = entry.get("request");
            if (urlPath.equals(req.get("url").asText())) {
                filtered.add(req);
            }
        }
        return filtered;
    }

    private String signToken(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
