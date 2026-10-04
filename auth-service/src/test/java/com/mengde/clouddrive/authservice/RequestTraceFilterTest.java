package com.mengde.clouddrive.authservice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RequestTraceFilterTest {
    private static final String TRACE = "1234567890abcdef1234567890abcdef";

    @Test void preservesSingleValidIdAcrossAnUnauthorizedDispatchAndRestoresCallerMdc() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.addHeader("X-Trace-Id", TRACE);
        MDC.put("traceId", "caller");
        try {
            new RequestTraceFilter().doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("traceId")).isEqualTo(TRACE);
                response.setStatus(401);
            });
            assertThat(response.getHeader("X-Trace-Id")).isEqualTo(TRACE);
            assertThat(MDC.get("traceId")).isEqualTo("caller");
        } finally { MDC.clear(); }
    }

    @Test void invalidMissingZeroAndMultipleValuesGenerateFreshIdsWithoutLeakingToNextRequest() throws Exception {
        String previous = null;
        for (String value : new String[] {"", "0".repeat(32), "raw-secret\r\nevent=forged", TRACE + "," + TRACE}) {
            var request = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            if (!value.isEmpty()) request.addHeader("X-Trace-Id", value);
            new RequestTraceFilter().doFilter(request, response, (req, res) -> {});
            String actual = response.getHeader("X-Trace-Id");
            assertThat(actual).matches("[0-9a-f]{32}").isNotEqualTo(value).isNotEqualTo(previous);
            assertThat(MDC.get("traceId")).isNull();
            previous = actual;
        }
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        request.addHeader("X-Trace-Id", TRACE);
        request.addHeader("X-Trace-Id", TRACE);
        new RequestTraceFilter().doFilter(request, response, (req, res) -> {});
        assertThat(response.getHeader("X-Trace-Id")).isNotEqualTo(TRACE);
    }

    @Test void exceptionStillCleansMdcAndEventDoesNotContainRawPathHeadersOrErrorMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestTraceFilter.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        var request = new MockHttpServletRequest("GET", "/api/shares/private-token/download");
        request.setQueryString("passcode=private-passcode");
        request.addHeader("Authorization", "Bearer private-jwt");
        request.addHeader("X-Trace-Id", TRACE);
        var response = new MockHttpServletResponse();
        try {
            assertThatThrownBy(() -> new RequestTraceFilter().doFilter(request, response, (req, res) -> {
                throw new ServletException("private-error");
            })).isInstanceOf(ServletException.class).hasMessage("private-error");
            assertThat(MDC.get("traceId")).isNull();
            assertThat(captured.list).hasSize(1);
            assertThat(captured.list.get(0).getFormattedMessage()).contains("traceId=" + TRACE, "failed=true")
                    .doesNotContain("private-token", "private-passcode", "private-jwt", "private-error");
        } finally { logger.detachAppender(captured); captured.stop(); MDC.clear(); }
    }
}

