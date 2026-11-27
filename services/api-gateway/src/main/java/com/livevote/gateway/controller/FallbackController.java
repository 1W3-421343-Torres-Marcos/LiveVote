package com.livevote.gateway.controller;

import com.livevote.gateway.exception.ApiErrorResponse;
import com.livevote.gateway.security.IdentityHeaderFilter;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Controlled Circuit Breaker fallback endpoints for downstream service outages.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/votes")
    public Mono<ResponseEntity<ApiErrorResponse>> votesFallback(ServerWebExchange exchange) {
        return buildFallback(exchange, "Votes service is temporarily unavailable");
    }

    @RequestMapping("/results")
    public Mono<ResponseEntity<ApiErrorResponse>> resultsFallback(ServerWebExchange exchange) {
        return buildFallback(exchange, "Results service is temporarily unavailable");
    }

    private Mono<ResponseEntity<ApiErrorResponse>> buildFallback(ServerWebExchange exchange, String message) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                resolveOriginalPath(exchange)
        );
        return Mono.just(ResponseEntity.status(status).body(body));
    }

    private String resolveOriginalPath(ServerWebExchange exchange) {
        String capturedPath = exchange.getAttribute(IdentityHeaderFilter.ATTR_ORIGINAL_PATH);
        if (capturedPath != null && !capturedPath.isBlank()) {
            return capturedPath;
        }
        LinkedHashSet<URI> originalUris = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originalUris != null && !originalUris.isEmpty()) {
            return originalUris.iterator().next().getPath();
        }
        return exchange.getRequest().getPath().value();
    }
}
