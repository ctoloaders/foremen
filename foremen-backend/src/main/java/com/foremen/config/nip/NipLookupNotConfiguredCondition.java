package com.foremen.config.nip;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * FOR-11-00 (Requirement 6.2) — exact negation of {@link NipLookupConfiguredCondition}, so exactly
 * one {@code NipLookupService} implementation is present in the context.
 */
public class NipLookupNotConfiguredCondition implements Condition {

    private final NipLookupConfiguredCondition configured = new NipLookupConfiguredCondition();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !configured.matches(context, metadata);
    }
}
