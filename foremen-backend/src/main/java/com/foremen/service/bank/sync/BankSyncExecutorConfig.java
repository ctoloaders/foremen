package com.foremen.service.bank.sync;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * FOR-07-04 (Requirement 5.4) — the dedicated, bounded executor running sync runs (and the Salt Edge
 * callback handler, {@code @Async("bankSyncExecutor")}). Sizes come from
 * {@code foremen.bank-feed.sync.executor.{core,max,queue}} (defaults 2 / 4 / 50). A full queue rejects
 * the submission ({@code AbortPolicy}) instead of blocking the callback / user request; the orchestrator
 * then fails the queued run so the connection is not blocked.
 */
@Configuration
@EnableAsync
public class BankSyncExecutorConfig {

    /** Bean name of the sync executor. */
    public static final String EXECUTOR = "bankSyncExecutor";

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor bankSyncExecutor(
            @Value("${foremen.bank-feed.sync.executor.core:2}") int core,
            @Value("${foremen.bank-feed.sync.executor.max:4}") int max,
            @Value("${foremen.bank-feed.sync.executor.queue:50}") int queue) {
        int corePool = Math.max(1, core);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(corePool);
        ex.setMaxPoolSize(Math.max(corePool, max));
        ex.setQueueCapacity(Math.max(0, queue));
        ex.setThreadNamePrefix("bank-sync-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }
}
