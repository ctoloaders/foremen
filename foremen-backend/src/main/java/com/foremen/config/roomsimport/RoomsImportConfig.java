package com.foremen.config.roomsimport;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.foremen.service.roomsimport.crop.RoomCropper;
import com.foremen.service.roomsimport.extract.RoomsInputExtractors;

/**
 * FOR-05-02 (design §B1, §B8) — enables the {@code foremen.rooms-import} properties and provides the
 * dedicated bounded pool the {@code RoomsImportWorker} runs on ({@code @Async("roomsImportTaskExecutor")}).
 * {@code @EnableAsync} is already activated by {@link com.foremen.config.ocr.OcrConfig}.
 *
 * <p>The pool is small and bounded, like {@code ocrTaskExecutor}: each job rasterizes pages and fans
 * out Gemini calls, so concurrency is limited to protect the heap. The one-job-per-project rule
 * keeps the real load low; {@code CallerRunsPolicy} throttles intake under a burst instead of
 * rejecting.
 */
@Configuration
@EnableConfigurationProperties(RoomsImportProperties.class)
public class RoomsImportConfig {

    @Bean("roomsImportTaskExecutor")
    public ThreadPoolTaskExecutor roomsImportTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("rooms-import-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }

    /** Content-sniffing facade over the text / office / PDF / image extractors (Requirement 6). */
    @Bean
    public RoomsInputExtractors roomsInputExtractors(RoomsImportProperties properties) {
        return new RoomsInputExtractors(properties);
    }

    /** Pure room cropper with the default 4 % margin (Requirement 9.1). */
    @Bean
    public RoomCropper roomCropper() {
        return new RoomCropper();
    }
}
