package com.mengde.clouddrive.authservice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    public static final String KEY = "traceId";

    public static String normalize(String value) {
        return value != null && value.matches("[0-9a-f]{32}") && !value.equals("0".repeat(32))
                ? value : UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = Collections.list(request.getHeaders(HEADER));
        String trace = normalize(values.size() == 1 ? values.get(0) : null);
        String previous = MDC.get(KEY);
        long start = System.nanoTime();
        boolean failed = true;
        MDC.put(KEY, trace);
        response.setHeader(HEADER, trace);
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            // This measures the synchronous servlet dispatch, not an async response body.
            LoggerFactory.getLogger(RequestTraceFilter.class).info(
                    "event=servlet_dispatch traceId={} status={} failed={} durationMs={}",
                    trace, response.getStatus(), failed, (System.nanoTime() - start) / 1_000_000);
            if (previous == null) MDC.remove(KEY); else MDC.put(KEY, previous);
        }
    }
}

