package com.livevote.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.livevote.gateway.config.JwtProperties;
import com.livevote.gateway.exception.GatewayErrorWriter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IdentityHeaderFilterTest {

    private static final String SECRET = "test-jwt-secret-with-at-least-32-bytes-length";

    private IdentityHeaderFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        JwtValidator jwtValidator = new JwtValidator(jwtProperties);
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        filter = new IdentityHeaderFilter(jwtValidator, new GatewayErrorWriter(mapper));
    }

    @Test
    void shouldStripSpoofedIdentityHeaderOnPublicRoutes() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/polls/abc-123")
                .header(IdentityHeaderFilter.HEADER_USER_ID, "spoofed-user")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().get(IdentityHeaderFilter.HEADER_USER_ID)).isNull();
    }

    @Test
    void shouldRejectProtectedRoutesWithoutValidJwtEvenWithSpoofedHeader() {
        String[] protectedPaths = {"/api/polls", "/api/polls/mine", "/api/polls/123/close"};
        for (int i = 0; i < protectedPaths.length; i++) {
            String path = protectedPaths[i];
            MockServerHttpRequest request = (i == 1
                    ? MockServerHttpRequest.get(path)
                    : MockServerHttpRequest.post(path))
                    .header(IdentityHeaderFilter.HEADER_USER_ID, UUID.randomUUID().toString())
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

            StepVerifier.create(filter.filter(exchange, ex -> {
                forwarded.set(ex);
                return Mono.empty();
            })).verifyComplete();

            assertThat(forwarded.get()).isNull();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void shouldReplaceSpoofedHeaderWithSubjectFromValidJwtOnProtectedRoutes() {
        UUID realUserId = UUID.randomUUID();
        String token = signToken(realUserId);

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/polls")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(IdentityHeaderFilter.HEADER_USER_ID, UUID.randomUUID().toString())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        })).verifyComplete();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().get(IdentityHeaderFilter.HEADER_USER_ID))
                .containsExactly(realUserId.toString());
    }

    private String signToken(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
