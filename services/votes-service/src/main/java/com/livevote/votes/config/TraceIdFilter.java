package com.livevote.votes.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts or generates X-Trace-Id, populates SLF4J MDC for JSON logs, and cleans up after each request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "traceId";

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedNanos = System.nanoTime();
        String traceId = resolveOrGenerateTraceId(request.getHeader(HEADER_TRACE_ID));
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            String path = request.getRequestURI();
            if (path.startsWith("/actuator/")) {
                log.debug("HTTP request completed method={} path={} status={} durationMs={}",
                        request.getMethod(), path, response.getStatus(), durationMs);
            } else {
                log.info("HTTP request completed method={} path={} status={} durationMs={}",
                        request.getMethod(), path, response.getStatus(), durationMs);
            }
            MDC.remove(MDC_TRACE_ID);
        }
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
