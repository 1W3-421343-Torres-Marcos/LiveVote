package com.livevote.gateway.security;

import com.livevote.gateway.exception.GatewayErrorWriter;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Strips any client-supplied X-User-Id header on every route, validates JWT on protected routes,
 * and injects a trusted X-User-Id header from the validated token subject.
 */
@Component
public class IdentityHeaderFilter implements GlobalFilter, Ordered {

    public static final int ORDER = -100;
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String ATTR_ORIGINAL_PATH = "livevote.originalPath";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern CLOSE_POLL_PATH = Pattern.compile("^/api/polls/[^/]+/close/?$");

    private final JwtValidator jwtValidator;
    private final GatewayErrorWriter errorWriter;

    public IdentityHeaderFilter(JwtValidator jwtValidator, GatewayErrorWriter errorWriter) {
        this.jwtValidator = jwtValidator;
        this.errorWriter = errorWriter;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        exchange.getAttributes().putIfAbsent(ATTR_ORIGINAL_PATH, request.getPath().value());
        if (!isProtectedRoute(request.getMethod(), request.getPath().value())) {
            ServerHttpRequest sanitized = request.mutate()
                    .headers(headers -> headers.remove(HEADER_USER_ID))
                    .build();
            return chain.filter(exchange.mutate().request(sanitized).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return errorWriter.writeError(exchange, HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return errorWriter.writeError(exchange, HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        UUID userId;
        try {
            userId = jwtValidator.validateAndExtractUserId(token);
        } catch (JwtException | IllegalArgumentException ex) {
            return errorWriter.writeError(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired JWT token");
        }

        ServerHttpRequest authenticated = request.mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.set(HEADER_USER_ID, userId.toString());
                })
                .build();
        return chain.filter(exchange.mutate().request(authenticated).build());
    }

    private boolean isProtectedRoute(HttpMethod method, String path) {
        if (HttpMethod.POST.equals(method) && ("/api/polls".equals(path) || "/api/polls/".equals(path))) {
            return true;
        }
        if (HttpMethod.GET.equals(method) && ("/api/polls/mine".equals(path) || "/api/polls/mine/".equals(path))) {
            return true;
        }
        return HttpMethod.POST.equals(method) && CLOSE_POLL_PATH.matcher(path).matches();
    }
}
