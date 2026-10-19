package com.foremen.config.ocr;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * FOR-06-03 (Requirement 3.6, design §5.2; Amendment A1 §A1.2) — enables the {@code foremen.ocr}
 * configuration properties so the namespace is bound at application startup, mirroring
 * {@link com.foremen.config.image.ImageStorageConfig}, and provides the dedicated thread pool that
 * runs asynchronous OCR processing off the request thread.
 *
 * <p>{@link OcrProperties} carries the master {@code enabled} flag, the Google Cloud Vision
 * credentials, and the LLM model + credentials — all read from configuration/environment, nothing
 * hardcoded. The concrete {@link com.foremen.service.ocr.OcrService} beans (the Google
 * implementation and the disabled fallback) are selected on {@code foremen.ocr.enabled} and consume
 * these properties.
 *
 * <p><b>Amendment A1.</b> {@code @EnableAsync} activates {@code @Async} support and
 * {@link #ocrTaskExecutor()} provides the pool the {@code ProcurementOcrWorker} runs on. The pool is
 * deliberately SMALL ({@code corePoolSize=1}, {@code maxPoolSize=2}, bounded queue) and uses
 * {@link ThreadPoolExecutor.CallerRunsPolicy} so OCR concurrency is bounded on the small VM: OCR is
 * CPU- and memory-heavy (PDF rasterization + Vision + Gemini), and bounding it protects the heap
 * (the dev OOM history). Under a burst the caller runs the task, naturally throttling intake rather
 * than queueing unboundedly or rejecting.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(OcrProperties.class)
public class OcrConfig {

    /**
     * Dedicated bounded thread pool for asynchronous OCR processing. Referenced by name from
     * {@code ProcurementOcrWorker}'s {@code @Async("ocrTaskExecutor")} listener so the OCR pipeline
     * never runs on the request thread (A1 R14.3).
     *
     * @return the OCR task executor
     */
    @Bean("ocrTaskExecutor")
    public ThreadPoolTaskExecutor ocrTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("ocr-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
