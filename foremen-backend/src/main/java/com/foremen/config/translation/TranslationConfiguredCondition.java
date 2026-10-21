package com.foremen.config.translation;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * FOR-06-12 (Requirement 10.1, 10.3, design §"MessageTranslationService seam") — matches when a
 * message-translation provider is CONFIGURED, i.e. the master switch {@code foremen.translation.enabled}
 * is {@code true}. Used to wire the Gemini-backed
 * {@link com.foremen.service.translation.GeminiMessageTranslationService} (task 2.4); the disabled
 * fallback {@link com.foremen.service.translation.DisabledMessageTranslationService} is wired on the
 * negation ({@link TranslationNotConfiguredCondition}), so exactly one implementation is present in
 * the context.
 *
 * <p>Mirrors the FOR-04-17 {@code ImageStorageConfiguredCondition} / FOR-06-03 OCR conditional
 * approach (a dedicated {@link Condition} keyed off the {@code foremen.translation.*} configuration
 * block) without importing any OCR code (R10.2). Task 2.4 owns the Gemini implementation and the
 * {@code foremen.translation.*} {@code TranslationProperties} record; this condition only needs the
 * master {@code enabled} flag to select the wiring, consistent with the OCR
 * {@code foremen.ocr.enabled} master switch.
 */
public class TranslationConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        return env.getProperty("foremen.translation.enabled", Boolean.class, Boolean.FALSE);
    }
}
