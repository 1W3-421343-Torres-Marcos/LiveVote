package com.livevote.gateway.controller;

import com.livevote.gateway.config.TraceIdFilter;
import com.livevote.gateway.exception.ApiErrorResponse;
import com.livevote.gateway.security.IdentityHeaderFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);
    private final MeterRegistry meterRegistry;

    public FallbackController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @RequestMapping("/votes")
    public Mono<ResponseEntity<ApiErrorResponse>> votesFallback(ServerWebExchange exchange) {
        return buildFallback(exchange, "votes", "Votes service is temporarily unavailable");
    }

    @RequestMapping("/results")
    public Mono<ResponseEntity<ApiErrorResponse>> resultsFallback(ServerWebExchange exchange) {
        return buildFallback(exchange, "results", "Results service is temporarily unavailable");
    }

    private Mono<ResponseEntity<ApiErrorResponse>> buildFallback(
            ServerWebExchange exchange,
            String routeName,
            String message
    ) {
        String originalPath = resolveOriginalPath(exchange);
        String traceId = resolveTraceId(exchange);

        meterRegistry.counter("livevote.gateway.fallback.total", "route", routeName).increment();
        try (MDC.MDCCloseable ignored = MDC.putCloseable(TraceIdFilter.MDC_TRACE_ID, traceId)) {
            log.warn("Circuit breaker fallback triggered route={} path={}", routeName, originalPath);
        }

        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                originalPath
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

    private String resolveTraceId(ServerWebExchange exchange) {
        String traceId = exchange.getAttribute(TraceIdFilter.ATTR_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        String header = exchange.getRequest().getHeaders().getFirst(TraceIdFilter.HEADER_TRACE_ID);
        return header != null && !header.isBlank() ? header : "unknown";
    }
}
