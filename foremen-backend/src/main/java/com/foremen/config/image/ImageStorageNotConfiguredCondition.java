package com.foremen.config.image;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when the shared image storage is NOT configured — the exact negation of
 * {@link ImageStorageConfiguredCondition} (FOR-04-17, Requirement 7.12). It is used to wire the
 * {@link com.foremen.service.image.DisabledImageStorage} fallback bean, so that exactly one of the
 * GCS-backed {@link com.foremen.service.image.GcsImageStorage} (active when configured) and the
 * disabled fallback (active when not configured) is present in the context.
 *
 * <p>Delegating to {@link ImageStorageConfiguredCondition} keeps the "configured" definition in a
 * single place: the fallback is active precisely when the GCS bean is not.
 */
public class ImageStorageNotConfiguredCondition implements Condition {

    private final ImageStorageConfiguredCondition configured = new ImageStorageConfiguredCondition();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !configured.matches(context, metadata);
    }
}
