package com.foremen.config.ocr;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-06-03 (Requirement 3.6, design §5.2) — enables the {@code foremen.ocr} configuration
 * properties so the namespace is bound at application startup, mirroring
 * {@link com.foremen.config.image.ImageStorageConfig}.
 *
 * <p>{@link OcrProperties} carries the master {@code enabled} flag, the Google Cloud Vision
 * credentials, and the LLM model + credentials — all read from configuration/environment, nothing
 * hardcoded. The concrete {@link com.foremen.service.ocr.OcrService} beans (the Google
 * implementation and the disabled fallback) are selected on {@code foremen.ocr.enabled} and consume
 * these properties.
 */
@Configuration
@EnableConfigurationProperties(OcrProperties.class)
public class OcrConfig {
}
