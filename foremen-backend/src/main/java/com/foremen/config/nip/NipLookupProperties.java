package com.foremen.config.nip;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * FOR-11-00 (Requirements 6.1, 6.2) — configuration for the NIP registry lookup, bound from
 * {@code foremen.nip-lookup}. Disabled by default; the timeout defaults to 5 seconds.
 */
@Validated
@ConfigurationProperties(prefix = "foremen.nip-lookup")
public record NipLookupProperties(boolean enabled, String baseUrl, String apiKey, Duration timeout) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    @ConstructorBinding
    public NipLookupProperties {
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
    }

    /**
     * @return {@code true} when enabled and a non-blank base URL is set
     */
    public boolean isConfigured() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }
}
