package com.foremen.config.estimateai;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * FOR-05-04 (Requirement 18.4) — enables the {@code foremen.estimate-ai} properties and provides the
 * dedicated bounded executor the {@code ESTIMATE}-mode worker runs on
 * ({@code @Async("estimateAiTaskExecutor")}), so it cannot starve the rooms-import or OCR pools.
 * {@code @EnableAsync} is already activated by {@link com.foremen.config.ocr.OcrConfig}.
 */
@Configuration
@EnableConfigurationProperties(EstimateAiProperties.class)
public class EstimateAiConfig {

    @Bean("estimateAiTaskExecutor")
    public ThreadPoolTaskExecutor estimateAiTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(10);
        ex.setThreadNamePrefix("estimate-ai-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
