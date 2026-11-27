package com.foremen.config.estimateai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * FOR-05-04 (Requirement 18.1; design B1/B5/B6) — configuration properties of the "Create estimate"
 * AI feature, bound from {@code foremen.estimate-ai}. Mirrors
 * {@link com.foremen.config.dashboard.DashboardAiProperties}: nested records are never {@code null}
 * and, in {@code application.yml}, the {@code llm.*} credentials default to the rooms-import values.
 *
 * <ul>
 *   <li>{@code enabled} — master switch for the {@code ESTIMATE} mode and the offer chat (default
 *       {@code false}).</li>
 *   <li>{@code llm} — Gemini settings; {@code stub=true} selects the deterministic stub clients.</li>
 *   <li>{@code maxCatalogChars} — size bound of the catalog context before batching (default 120000).</li>
 *   <li>{@code volumeTolerancePct} — a model volume differing from the engine volume by more than
 *       this percentage becomes a manual override (default 5).</li>
 *   <li>{@code scratchTimeout} — time box of the scratch (rollback-only) transaction (default 15 s).</li>
 *   <li>{@code timeout} — whole-job timeout covered by the stuck-job sweep (default 20 min).</li>
 *   <li>{@code chat} — offer chat limits.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "foremen.estimate-ai")
public record EstimateAiProperties(
        boolean enabled,
        Llm llm,
        int maxCatalogChars,
        double volumeTolerancePct,
        Duration scratchTimeout,
        Duration timeout,
        Chat chat) {

    public static final int DEFAULT_MAX_CATALOG_CHARS = 120_000;
    public static final double DEFAULT_VOLUME_TOLERANCE_PCT = 5.0;
    public static final Duration DEFAULT_SCRATCH_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration DEFAULT_JOB_TIMEOUT = Duration.ofMinutes(20);
    public static final Duration DEFAULT_LLM_TIMEOUT = Duration.ofMinutes(5);

    @ConstructorBinding
    public EstimateAiProperties {
        if (llm == null) {
            llm = new Llm(false, null, null, null, null, null, null, null);
        }
        if (maxCatalogChars <= 0) {
            maxCatalogChars = DEFAULT_MAX_CATALOG_CHARS;
        }
        if (volumeTolerancePct <= 0) {
            volumeTolerancePct = DEFAULT_VOLUME_TOLERANCE_PCT;
        }
        if (scratchTimeout == null || scratchTimeout.isZero() || scratchTimeout.isNegative()) {
            scratchTimeout = DEFAULT_SCRATCH_TIMEOUT;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = DEFAULT_JOB_TIMEOUT;
        }
        if (chat == null) {
            chat = new Chat(0, 0, 0, 0);
        }
    }

    /**
     * Gemini client settings.
     *
     * @param stub        {@code true} => deterministic stub clients (tests / Docker)
     * @param model       the Gemini model id
     * @param temperature sampling temperature (default 0)
     * @param timeout     per-call timeout (default 5 minutes)
     * @param project     Google Cloud project (enterprise/Vertex backend), or blank
     * @param location    Google Cloud location (enterprise/Vertex backend), or blank
     * @param apiKey      Gemini Developer API key, or blank
     * @param credentials credentials source
     */
    public record Llm(boolean stub, String model, Double temperature, Duration timeout, String project,
                      String location, String apiKey, Credentials credentials) {
        @ConstructorBinding
        public Llm {
            if (temperature == null) {
                temperature = 0.0;
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                timeout = DEFAULT_LLM_TIMEOUT;
            }
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

    /**
     * Offer chat limits (Requirements 10, 11).
     *
     * @param maxToolRounds    maximum model/tool rounds per turn (default 8)
     * @param historyTurns     history turns sent to the model (default 12)
     * @param confirmThreshold lines affected above which a confirmation is required (default 20)
     * @param rateLimit        chat turns per user and hour (default 60)
     */
    public record Chat(int maxToolRounds, int historyTurns, int confirmThreshold, int rateLimit) {
        @ConstructorBinding
        public Chat {
            if (maxToolRounds <= 0) {
                maxToolRounds = 8;
            }
            if (historyTurns <= 0) {
                historyTurns = 12;
            }
            if (confirmThreshold <= 0) {
                confirmThreshold = 20;
            }
            if (rateLimit <= 0) {
                rateLimit = 60;
            }
        }
    }
}
