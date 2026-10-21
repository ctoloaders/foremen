package com.foremen.config.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * FOR-06-12 (Requirement 10.2, 10.4, design §"MessageTranslationService seam") — configuration
 * properties for the backend {@link com.foremen.service.translation.MessageTranslationService},
 * bound from the {@code foremen.translation} namespace in {@code application.yml} (and the
 * per-profile {@code application-*.yml}). Mirrors the {@code foremen.ocr}
 * ({@link com.foremen.config.ocr.OcrProperties}) approach — the Google Gen AI (Gemini) wiring
 * shape — without importing or reusing any OCR code (R10.2): every value is read from
 * configuration/environment, nothing is hardcoded.
 *
 * <p>The binding is intentionally lenient: every field may legitimately be empty in environments
 * where translation is not configured. In that case {@code enabled} is {@code false} and the wiring
 * selects the {@link com.foremen.service.translation.DisabledMessageTranslationService} fallback
 * (R10.3) via {@link TranslationNotConfiguredCondition}, so the application starts cleanly without a
 * Gemini provider.
 *
 * <ul>
 *   <li>{@code enabled} — the master switch. When {@code true}, the
 *       {@link com.foremen.service.translation.GeminiMessageTranslationService} bean is active
 *       ({@link TranslationConfiguredCondition}); otherwise the disabled fallback is wired.</li>
 *   <li>{@code model} — the Google LLM (Gemini) model id used for translation + locale
 *       classification (e.g. {@code gemini-3.8-flash}).</li>
 *   <li>{@code project} / {@code location} — the Google Cloud project + location used when the LLM
 *       runs against the Gemini Enterprise Agent Platform (Vertex) backend; both may be blank to use
 *       the Gemini Developer API with an {@code api-key} instead.</li>
 *   <li>{@code api-key} — an optional Gemini Developer API key (alternative to project/location
 *       ADC). Blank when using the enterprise (Vertex) backend.</li>
 *   <li>{@code credentials} — how the Gemini client obtains credentials (an explicit service-account
 *       JSON key {@code path}, or a resource {@code location} / {@code application-default}
 *       sentinel), mirroring the OCR credentials shape.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "foremen.translation")
public record TranslationProperties(
        boolean enabled,
        String model,
        String project,
        String location,
        String apiKey,
        Credentials credentials) {

    /**
     * Designated binding constructor. Ensures the nested {@code credentials} record is never
     * {@code null} so callers can read {@code credentials()} without a null check even when the
     * whole {@code foremen.translation} namespace is absent.
     */
    @ConstructorBinding
    public TranslationProperties {
        if (credentials == null) {
            credentials = new Credentials(null, null);
        }
    }

    /**
     * Whether a translation provider is configured and enabled. Mirrors
     * {@link com.foremen.config.ocr.OcrProperties#isConfigured()}: the master {@code enabled} flag is
     * the gate the wiring uses to pick the enabled vs disabled implementation (R10.3).
     *
     * @return {@code true} when {@code foremen.translation.enabled} is {@code true}
     */
    public boolean isConfigured() {
        return enabled;
    }

    /**
     * Google Cloud credentials source, identical in shape to
     * {@link com.foremen.config.ocr.OcrProperties.Credentials}. Either {@code path} (an explicit
     * service-account JSON key file path) or {@code location} (a resource location /
     * {@code application-default} sentinel) may be set; both may be empty when translation is not
     * configured.
     *
     * @param path     explicit service-account JSON key file path, or {@code null}
     * @param location resource location for credentials (e.g. {@code file:}/{@code classpath:}), or
     *                 {@code null}
     */
    public record Credentials(String path, String location) {
    }
}
