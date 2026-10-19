package com.foremen.config.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * FOR-06-03 (Requirement 3.6, design §5.2) — configuration properties for the backend
 * {@link com.foremen.service.ocr.OcrService}, bound from the {@code foremen.ocr} namespace in
 * {@code application.yml} (and the per-profile {@code application-*.yml}). Mirrors the
 * {@code foremen.image-storage} ({@link com.foremen.config.image.ImageStorageProperties}) approach:
 * every value is read from configuration/environment — nothing is hardcoded.
 *
 * <p>The binding is intentionally lenient: every field may legitimately be empty in environments
 * where OCR is not configured. In that case {@code enabled} is {@code false} and the wiring selects
 * the {@link com.foremen.service.ocr.DisabledOcrService} fallback (R3.5), so the application starts
 * cleanly without a Vision/LLM provider.
 *
 * <ul>
 *   <li>{@code enabled} — the master switch. When {@code true}, the
 *       {@link com.foremen.service.ocr.GoogleOcrService} bean is active; otherwise the disabled
 *       fallback is wired.</li>
 *   <li>{@code vision.credentials} — how the Google Cloud Vision client obtains credentials (an
 *       explicit service-account JSON key {@code path}, or a resource {@code location} /
 *       {@code application-default} sentinel), mirroring the image-storage credentials shape.</li>
 *   <li>{@code llm.model} — the Google LLM (Gemini) model id used for corner detection + per-line
 *       parse (e.g. {@code gemini-3.8-flash}).</li>
 *   <li>{@code llm.credentials} — credentials for the LLM client, same shape as the vision
 *       credentials.</li>
 *   <li>{@code llm.project} / {@code llm.location} — the Google Cloud project + location used when
 *       the LLM runs against the Gemini Enterprise Agent Platform (Vertex) backend; both may be
 *       blank to use the Gemini Developer API with an {@code api-key} instead.</li>
 *   <li>{@code llm.api-key} — an optional Gemini Developer API key (alternative to project/location
 *       ADC). Blank when using the enterprise (Vertex) backend.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "foremen.ocr")
public record OcrProperties(
        boolean enabled,
        Vision vision,
        Llm llm) {

    /**
     * Designated binding constructor. Ensures the nested records are never {@code null} so callers
     * can read {@code vision()}/{@code llm()} without a null check even when the whole
     * {@code foremen.ocr} namespace is absent.
     */
    @ConstructorBinding
    public OcrProperties {
        if (vision == null) {
            vision = new Vision(new Credentials(null, null));
        }
        if (llm == null) {
            llm = new Llm(null, new Credentials(null, null), null, null, null);
        }
    }

    /**
     * Whether OCR is configured and enabled. Mirrors
     * {@link com.foremen.config.image.ImageStorageProperties#isConfigured()}: the master
     * {@code enabled} flag is the gate the wiring uses to pick the enabled vs disabled
     * implementation (R3.5).
     *
     * @return {@code true} when {@code foremen.ocr.enabled} is {@code true}
     */
    public boolean isConfigured() {
        return enabled;
    }

    /**
     * Google Cloud Vision configuration.
     *
     * @param credentials the Vision client credentials source
     */
    public record Vision(Credentials credentials) {
        @ConstructorBinding
        public Vision {
            if (credentials == null) {
                credentials = new Credentials(null, null);
            }
        }
    }

    /**
     * Google LLM (Gemini) configuration for corner detection + per-line parse.
     *
     * @param model       the Gemini model id (e.g. {@code gemini-3.8-flash})
     * @param credentials the LLM client credentials source
     * @param project     the Google Cloud project id (enterprise / Vertex backend), or {@code null}
     * @param location    the Google Cloud location (enterprise / Vertex backend), or {@code null}
     * @param apiKey      an optional Gemini Developer API key (Developer-API backend), or {@code null}
     */
    public record Llm(String model, Credentials credentials, String project, String location,
                      String apiKey) {
        @ConstructorBinding
        public Llm {
            if (credentials == null) {
                credentials = new Credentials(null, null);
            }
        }
    }

    /**
     * Google Cloud credentials source, identical in shape to
     * {@link com.foremen.config.image.ImageStorageProperties.Credentials}. Either {@code path} (an
     * explicit service-account JSON key file path) or {@code location} (a resource location /
     * {@code application-default} sentinel) may be set; both may be empty when OCR is not
     * configured.
     *
     * @param path     explicit service-account JSON key file path, or {@code null}
     * @param location resource location for credentials (e.g. {@code file:}/{@code classpath:}), or
     *                 {@code null}
     */
    public record Credentials(String path, String location) {
    }
}
