package com.foremen.config.nip;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * FOR-11-00 (Requirement 6.2) — matches when the NIP registry is configured: {@code
 * foremen.nip-lookup.enabled=true} and {@code foremen.nip-lookup.base-url} is non-blank.
 */
public class NipLookupConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        boolean enabled = env.getProperty("foremen.nip-lookup.enabled", Boolean.class, Boolean.FALSE);
        String baseUrl = env.getProperty("foremen.nip-lookup.base-url");
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }
}
