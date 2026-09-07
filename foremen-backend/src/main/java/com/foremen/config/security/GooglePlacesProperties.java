package com.foremen.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the Google Places proxy, bound from the
 * {@code foremen.google-places} namespace in {@code application.yml} (and
 * {@code application-docker.yml}).
 *
 * <p>Holds the server-side Google Places API key used by the address autocomplete/details
 * proxy so the key never reaches the browser (FOR-04-13, Requirement 5.3, 5.4). The key
 * binds from {@code ${GOOGLE_PLACES_API_KEY:}} and may legitimately be empty in
 * environments where the Places feature is not enabled.
 *
 * <p>Fail-fast (Requirement 5.5): when {@link #enabled()} is {@code true} the {@link #apiKey()}
 * must not be blank. The validation runs in the record's canonical constructor, which executes
 * during property binding at context startup — so a misconfigured "enabled without a key"
 * combination aborts application startup with a clear configuration error rather than failing
 * later at request time. A record cannot use {@code @PostConstruct} directly, so the canonical
 * constructor is the natural place for this binding-time invariant.
 */
@Validated
@ConfigurationProperties(prefix = "foremen.google-places")
public record GooglePlacesProperties(boolean enabled, String apiKey) {

    /**
     * Canonical constructor enforcing the fail-fast invariant: if the Google Places feature is
     * enabled, an API key is mandatory. Throwing here surfaces as a context-startup failure with
     * an actionable message (Requirement 5.5).
     */
    public GooglePlacesProperties {
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "foremen.google-places.enabled is true but foremen.google-places.api-key "
                            + "(env GOOGLE_PLACES_API_KEY) is blank; set the Google Places API key "
                            + "or disable the feature.");
        }
    }
}
