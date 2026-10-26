package com.mengde.clouddrive.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Dedicated bounded worker: never run cleanup on a transaction's commit thread. */
@Configuration(proxyBeanMethods = false)
public class TrashCleanupExecutorConfiguration {
    @Bean("trashObjectCleanupExecutor")
    public ThreadPoolTaskExecutor trashObjectCleanupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("trash-object-cleanup-");
        // CallerRunsPolicy would borrow another connection while the commit thread still
        // holds its old one. Reject instead; the service logs the keys for manual retry.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
