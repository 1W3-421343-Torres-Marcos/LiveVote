package com.livevote.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void shouldPreserveValidTraceIdAndPropagateInRequestAttributeAndResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/results/poll-1")
                        .header(TraceIdFilter.HEADER_TRACE_ID, "trace-valid_123")
                        .build()
        );
        AtomicReference<String> forwardedHeader = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwardedHeader.set(ex.getRequest().getHeaders().getFirst(TraceIdFilter.HEADER_TRACE_ID));
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(forwardedHeader.get()).isEqualTo("trace-valid_123");
        assertThat((String) exchange.getAttribute(TraceIdFilter.ATTR_TRACE_ID)).isEqualTo("trace-valid_123");
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdFilter.HEADER_TRACE_ID))
                .isEqualTo("trace-valid_123");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    void shouldReplaceInvalidOrMissingTraceIdWithGeneratedHexId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/results/poll-1")
                        .header(TraceIdFilter.HEADER_TRACE_ID, "bad trace id with spaces!")
                        .build()
        );
        AtomicReference<String> forwardedHeader = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwardedHeader.set(ex.getRequest().getHeaders().getFirst(TraceIdFilter.HEADER_TRACE_ID));
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        String generated = forwardedHeader.get();
        assertThat(generated).matches("^[a-f0-9]{32}$");
        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdFilter.HEADER_TRACE_ID))
                .isEqualTo(generated);
    }
}
