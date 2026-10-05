package com.mengde.clouddrive.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiSettings.class)
public class AiConfiguration {
    @Bean(destroyMethod = "close")
    SummaryService summaryService(AiSettings settings) {
        return new SummaryService(settings, settings.enabled() ? new DeepSeekClient(settings) : null);
    }
}
