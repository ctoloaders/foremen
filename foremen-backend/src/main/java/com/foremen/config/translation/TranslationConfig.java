package com.foremen.config.translation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-06-12 (Requirement 10.2, 10.4, design §"MessageTranslationService seam") — enables the
 * {@code foremen.translation} configuration properties so the namespace is bound at application
 * startup, mirroring {@link com.foremen.config.ocr.OcrConfig} /
 * {@link com.foremen.config.image.ImageStorageConfig}.
 *
 * <p>{@link TranslationProperties} carries the master {@code enabled} flag, the Gemini model, the
 * project/location or api-key backend selector, and the credentials source — all read from
 * configuration/environment, nothing hardcoded (R10.2). The concrete
 * {@link com.foremen.service.translation.MessageTranslationService} beans (the Gemini-backed
 * {@link com.foremen.service.translation.GeminiMessageTranslationService} and the disabled fallback
 * {@link com.foremen.service.translation.DisabledMessageTranslationService}) are selected on
 * {@code foremen.translation.enabled} via {@link TranslationConfiguredCondition} /
 * {@link TranslationNotConfiguredCondition} and consume these properties.
 */
@Configuration
@EnableConfigurationProperties(TranslationProperties.class)
public class TranslationConfig {
}
