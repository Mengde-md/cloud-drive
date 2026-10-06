package com.mengde.clouddrive.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class AgentConfiguration {
    @Bean AgentTelemetry agentTelemetry(MeterRegistry meters) { return new AgentTelemetry(meters); }
    @Bean AgentService agentService(RagIndex index, SummaryService generation,
                                    @Value("${clouddrive.agent.enabled:false}") boolean enabled, AgentTelemetry telemetry) {
        return new AgentService(index, generation, enabled, telemetry);
    }
}
