package com.foremen.config.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * FOR-10 (Requirement 13.4; design "AI assistant") — configuration properties for the dashboard
 * widget AI assistant, bound from the {@code foremen.dashboard.ai} namespace. Mirrors
 * {@link com.foremen.config.roomsimport.RoomsImportProperties}: nested records are never {@code null}
 * and, in {@code application.yml}, the {@code llm.*} values default to the rooms-import / OCR values.
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default {@code false}).</li>
 *   <li>{@code rateLimitPerMinute} — per-user ai-draft calls per minute (default 10).</li>
 *   <li>{@code llm} — Gemini settings; {@code stub=true} selects the deterministic stub client.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "foremen.dashboard.ai")
public record DashboardAiProperties(
        boolean enabled,
        int rateLimitPerMinute,
        Llm llm) {

    @ConstructorBinding
    public DashboardAiProperties {
        if (rateLimitPerMinute <= 0) {
            rateLimitPerMinute = 10;
        }
        if (llm == null) {
            llm = new Llm(false, null, null, null, null, new Credentials(null, null));
        }
    }

    /**
     * Gemini client settings.
     *
     * @param stub        {@code true} => deterministic stub draft client (tests / Docker)
     * @param model       the Gemini model id
     * @param project     Google Cloud project (enterprise/Vertex backend), or blank
     * @param location    Google Cloud location (enterprise/Vertex backend), or blank
     * @param apiKey      Gemini Developer API key, or blank
     * @param credentials credentials source
     */
    public record Llm(boolean stub, String model, String project, String location, String apiKey,
                      Credentials credentials) {
        @ConstructorBinding
        public Llm {
            if (credentials == null) {
                credentials = new Credentials(null, null);
            }
        }
    }

    /**
     * @param path     explicit service-account JSON key file path, or blank
     * @param location resource location for credentials, or blank
     */
    public record Credentials(String path, String location) {
    }
}
