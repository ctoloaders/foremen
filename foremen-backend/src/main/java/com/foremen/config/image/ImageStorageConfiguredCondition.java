package com.foremen.config.image;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when the shared image storage is CONFIGURED — i.e. both the GCS bucket name and the CDN
 * base URL are present and non-blank under the {@code foremen.image-storage} namespace (FOR-04-17,
 * Requirement 7.11, 7.12). This mirrors {@link ImageStorageProperties#isConfigured()} at the
 * bean-wiring level so the concrete {@link com.foremen.service.image.GcsImageStorage} bean is
 * active only when storage is configured; the disabled fallback (task 3.3) is wired on the
 * negation.
 *
 * <p>A dedicated {@link Condition} is used rather than {@code @ConditionalOnProperty} because two
 * distinct properties (bucket AND cdn-base) must both be present, which a single-property
 * {@code @ConditionalOnProperty} cannot express.
 */
public class ImageStorageConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String bucket = env.getProperty("foremen.image-storage.bucket");
        String cdnBase = env.getProperty("foremen.image-storage.cdn-base");
        return bucket != null && !bucket.isBlank()
                && cdnBase != null && !cdnBase.isBlank();
    }
}
