package com.mengde.clouddrive.userservice;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FeignTraceConfiguration {
    @Bean
    RequestInterceptor traceHeader() {
        return template -> {
            template.removeHeader(RequestTraceFilter.HEADER);
            String trace = MDC.get(RequestTraceFilter.KEY);
            if (trace != null) template.header(RequestTraceFilter.HEADER, RequestTraceFilter.normalize(trace));
        };
    }
}
