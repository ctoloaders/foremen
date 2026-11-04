package com.foremen.service.bank.detect;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * FOR-07-04 (Requirements 15.6, 28.2) — the small pool running the asynchronous detection work: the AI
 * classifier step after a detection batch and the reverse document matching after an OCR parse. Bounded
 * (1–2 threads); a full queue drops the work (logged by the caller) instead of blocking a sync or the OCR
 * worker — dropped lines simply stay {@code UNLINKED} and can be re-run from the ledger.
 */
@Configuration
public class BankDetectionExecutorConfig {

    /** Bean name of the detection executor. */
    public static final String EXECUTOR = "bankDetectionExecutor";

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor bankDetectionExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("bank-detect-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }
}
