package com.livevote.gateway.config;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Generates or validates X-Trace-Id, propagates it upstream/downstream, and emits request completion logs with MDC.
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final int ORDER = -300;
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String ATTR_TRACE_ID = "livevote.traceId";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startedNanos = System.nanoTime();
        String traceId = resolveOrGenerateTraceId(exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID));

        exchange.getAttributes().put(ATTR_TRACE_ID, traceId);
        exchange.getResponse().getHeaders().set(HEADER_TRACE_ID, traceId);
        // Ensure a single X-Trace-Id response header even when upstream also echoes it.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER_TRACE_ID, traceId);
            return Mono.empty();
        });

        ServerHttpRequest tracedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER_TRACE_ID, traceId))
                .build();
        ServerWebExchange tracedExchange = exchange.mutate().request(tracedRequest).build();

        return chain.filter(tracedExchange).doFinally(signalType -> {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            HttpStatusCode status = tracedExchange.getResponse().getStatusCode();
            int statusCode = status != null ? status.value() : 0;
            try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_TRACE_ID, traceId)) {
                log.info("Gateway request completed method={} path={} status={} durationMs={}",
                        tracedRequest.getMethod(),
                        tracedRequest.getPath().value(),
                        statusCode,
                        durationMs);
            }
        });
    }

    private String resolveOrGenerateTraceId(String incoming) {
        if (incoming != null) {
            String trimmed = incoming.trim();
            if (VALID_TRACE_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
