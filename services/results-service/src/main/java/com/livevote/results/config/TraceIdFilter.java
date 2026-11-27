package com.livevote.results.config;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Extracts or generates X-Trace-Id on WebFlux requests and logs completion with MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements WebFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "traceId";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startedNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest();
        String traceId = resolveOrGenerateTraceId(request.getHeaders().getFirst(HEADER_TRACE_ID));
        exchange.getResponse().getHeaders().set(HEADER_TRACE_ID, traceId);

        return chain.filter(exchange).doFinally(signalType -> {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            int statusCode = status != null ? status.value() : 0;
            String path = request.getPath().value();
            try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_TRACE_ID, traceId)) {
                if (path.startsWith("/actuator/")) {
                    log.debug("WebFlux request completed method={} path={} status={} durationMs={}",
                            request.getMethod(), path, statusCode, durationMs);
                } else {
                    log.info("WebFlux request completed method={} path={} status={} durationMs={}",
                            request.getMethod(), path, statusCode, durationMs);
                }
            }
        });
    }

    public static String resolveOrGenerateTraceId(String incoming) {
        if (incoming != null) {
            String trimmed = incoming.trim();
            if (VALID_TRACE_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
