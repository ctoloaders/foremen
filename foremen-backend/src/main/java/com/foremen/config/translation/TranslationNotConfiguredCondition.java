package com.foremen.config.translation;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * FOR-06-12 (Requirement 10.3, design §"MessageTranslationService seam") — matches when a
 * message-translation provider is NOT configured: the exact negation of
 * {@link TranslationConfiguredCondition}. Used to wire the
 * {@link com.foremen.service.translation.DisabledMessageTranslationService} fallback bean, so that
 * exactly one of the Gemini-backed
 * {@link com.foremen.service.translation.GeminiMessageTranslationService} (active when configured,
 * task 2.4) and the disabled fallback (active when not configured) is present in the context.
 *
 * <p>Delegating to {@link TranslationConfiguredCondition} keeps the "configured" definition in a
 * single place: the fallback is active precisely when the provider bean is not. Mirrors the
 * FOR-04-17 {@code ImageStorageNotConfiguredCondition} approach without importing OCR code (R10.2).
 */
public class TranslationNotConfiguredCondition implements Condition {

    private final TranslationConfiguredCondition configured = new TranslationConfiguredCondition();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !configured.matches(context, metadata);
    }
}
