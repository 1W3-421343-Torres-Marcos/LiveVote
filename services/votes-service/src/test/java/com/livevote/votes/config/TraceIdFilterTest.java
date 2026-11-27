package com.livevote.votes.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void shouldPopulateAndClearMdcAndEchoTraceHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/polls/mine");
        request.addHeader(TraceIdFilter.HEADER_TRACE_ID, "trace-votes-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest servletRequest,
                    jakarta.servlet.ServletResponse servletResponse
            ) {
                mdcInsideChain.set(MDC.get(TraceIdFilter.MDC_TRACE_ID));
            }
        });

        assertThat(mdcInsideChain.get()).isEqualTo("trace-votes-abc");
        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo("trace-votes-abc");
        assertThat(response.getHeader(TraceIdFilter.HEADER_INSTANCE_ID)).isNotBlank();
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    void shouldGenerateTraceIdWhenHeaderMissingOrInvalidAndCleanUpEvenOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader(TraceIdFilter.HEADER_TRACE_ID, "bad$trace");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).matches("^[a-f0-9]{32}$");
                throw new RuntimeException("boom");
            });
        } catch (Exception expected) {
            assertThat(expected.getMessage()).isEqualTo("boom");
        }

        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).matches("^[a-f0-9]{32}$");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }
}
