package com.foremen.config.roomsimport;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * FOR-05-02 (Requirements 1.4, 1.5, 1.7, 13.1, 13.3; design §B8) — configuration properties for the
 * rooms auto-import feature, bound from the {@code foremen.rooms-import} namespace. Follows the
 * {@link com.foremen.config.ocr.OcrProperties} approach: every value comes from configuration or
 * environment, nothing is hardcoded, and the nested records are never {@code null}.
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default {@code false}); independent of OCR.</li>
 *   <li>{@code llm} — Gemini settings; in {@code application.yml} they default to the
 *       {@code foremen.ocr.llm.*} values.</li>
 *   <li>{@code limits} — input limits (file count, per-file size, PDF pages, minimum text-layer
 *       characters before a PDF is treated as scanned).</li>
 *   <li>{@code ttl} — result retention and the stuck-job timeout.</li>
 *   <li>{@code maxConcurrency} — upper bound on concurrent per-room Gemini calls within one job.</li>
 *   <li>{@code validation} — thresholds used by the draft validators.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "foremen.rooms-import")
public record RoomsImportProperties(
        boolean enabled,
        Llm llm,
        Limits limits,
        Ttl ttl,
        int maxConcurrency,
        Validation validation) {

    @ConstructorBinding
    public RoomsImportProperties {
        if (llm == null) {
            llm = new Llm(null, null, null, null, new Credentials(null, null));
        }
        if (limits == null) {
            limits = new Limits(0, null, 0, 0);
        }
        if (ttl == null) {
            ttl = new Ttl(null, null);
        }
        if (maxConcurrency <= 0) {
            maxConcurrency = 4;
        }
        if (validation == null) {
            validation = new Validation(0, 0, 0, 0);
        }
    }

    /**
     * Gemini client settings (same shape as {@link com.foremen.config.ocr.OcrProperties.Llm}).
     *
     * @param model       the Gemini model id
     * @param project     Google Cloud project (enterprise/Vertex backend), or blank
     * @param location    Google Cloud location (enterprise/Vertex backend), or blank
     * @param apiKey      Gemini Developer API key, or blank
     * @param credentials credentials source
     */
    public record Llm(String model, String project, String location, String apiKey,
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

    /**
     * Input limits. Defaults apply when a value is absent/non-positive: 10 files, 20MB per file,
     * 50 PDF pages, 40 text-layer characters.
     */
    public record Limits(int maxFiles, DataSize maxFileSize, int maxPdfPages, int minPdfTextChars) {
        @ConstructorBinding
        public Limits {
            if (maxFiles <= 0) {
                maxFiles = 10;
            }
            if (maxFileSize == null || maxFileSize.toBytes() <= 0) {
                maxFileSize = DataSize.ofMegabytes(20);
            }
            if (maxPdfPages <= 0) {
                maxPdfPages = 50;
            }
            if (minPdfTextChars <= 0) {
                minPdfTextChars = 40;
            }
        }
    }

    /**
     * Retention. Defaults: result kept 72h, a non-terminal job older than 15m is considered stuck.
     */
    public record Ttl(Duration result, Duration stuckTimeout) {
        @ConstructorBinding
        public Ttl {
            if (result == null || result.isZero() || result.isNegative()) {
                result = Duration.ofHours(72);
            }
            if (stuckTimeout == null || stuckTimeout.isZero() || stuckTimeout.isNegative()) {
                stuckTimeout = Duration.ofMinutes(15);
            }
        }
    }

    /**
     * Validator thresholds. Defaults: area tolerance 0.02, ceiling 1.8..6.0 m, low confidence 0.6.
     */
    public record Validation(double areaTolerance, double ceilingMin, double ceilingMax,
                             double lowConfidence) {
        @ConstructorBinding
        public Validation {
            if (areaTolerance <= 0) {
                areaTolerance = 0.02;
            }
            if (ceilingMin <= 0) {
                ceilingMin = 1.8;
            }
            if (ceilingMax <= 0) {
                ceilingMax = 6.0;
            }
            if (lowConfidence <= 0) {
                lowConfidence = 0.6;
            }
        }
    }
}
