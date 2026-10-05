package com.mengde.clouddrive.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfiguration {
    @Bean AgentService agentService(RagIndex index, SummaryService generation,
                                    @Value("${clouddrive.agent.enabled:false}") boolean enabled) {
        return new AgentService(index, generation, enabled);
    }
}
