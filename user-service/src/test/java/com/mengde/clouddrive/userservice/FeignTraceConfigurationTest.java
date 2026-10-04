package com.mengde.clouddrive.userservice;

import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

class FeignTraceConfigurationTest {
    @Test void userToAuthCallCarriesCurrentIdAndClearsAnOldHeaderWithoutContext() {
        var interceptor = new FeignTraceConfiguration().traceHeader();
        var template = new RequestTemplate(); template.header("X-Trace-Id", "old-id");
        String trace = "1234567890abcdef1234567890abcdef";
        MDC.put("traceId", trace);
        try {
            interceptor.apply(template);
            assertThat(template.headers().get("X-Trace-Id")).containsExactly(trace);
            MDC.clear(); interceptor.apply(template);
            assertThat(template.headers()).doesNotContainKey("X-Trace-Id");
        } finally { MDC.clear(); }
    }
}
