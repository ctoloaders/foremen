package com.foremen.config.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the shared {@link com.foremen.service.image.ImageStorage} service,
 * bound from the {@code foremen.image-storage} namespace in {@code application.yml} (and the
 * per-profile {@code application-*.yml}). All values are read from configuration/environment —
 * nothing is hardcoded (FOR-04-17, Requirement 7.11).
 *
 * <p>The binding is intentionally lenient: every field may legitimately be empty in environments
 * where image storage is not configured. In that case {@link #isConfigured()} reports {@code false}
 * and the wiring selects the disabled fallback implementation (Requirement 7.12), so the
 * application starts cleanly without a bucket, CDN base or credentials. Fail-fast validation of a
 * fully/partially configured storage belongs to the concrete GCS wiring (task 3.2), not to this
 * binding.
 *
 * <ul>
 *   <li>{@code bucket} — the Google Cloud Storage bucket name.</li>
 *   <li>{@code cdn-base} — the public CDN base URL from which read-time image URLs are built
 *       ({@code cdnBase + "/" + objectKey}).</li>
 *   <li>{@code credentials} — how the GCS client obtains credentials: an explicit service-account
 *       JSON key {@link Credentials#path() path}, or a {@link Credentials#location() location}
 *       (e.g. {@code classpath:}/{@code file:} resource or {@code application-default} for
 *       Application Default Credentials).</li>
 *   <li>{@code max-upload-size} — the maximum accepted upload size (a Spring
 *       {@link org.springframework.util.unit.DataSize} string such as {@code 5MB}), used to reject
 *       oversized uploads (Requirement 7.7).</li>
 *   <li>{@code reconciliation} — the periodic orphan-reconciliation schedule (Requirement 7.10),
 *       expressed as a {@link Reconciliation#cron() cron} expression and/or a fixed
 *       {@link Reconciliation#interval() interval}.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "foremen.image-storage")
public record ImageStorageProperties(
        String bucket,
        String cdnBase,
        Credentials credentials,
        String maxUploadSize,
        Reconciliation reconciliation) {

    /**
     * Designated binding constructor. Ensures the nested records are never {@code null} so callers
     * can read {@code credentials()}/{@code reconciliation()} without a null check even when the
     * whole {@code foremen.image-storage} namespace is absent.
     */
    @ConstructorBinding
    public ImageStorageProperties {
        if (credentials == null) {
            credentials = new Credentials(null, null);
        }
        if (reconciliation == null) {
            reconciliation = new Reconciliation(null, null);
        }
    }

    /**
     * Whether image storage is configured: a bucket name and a CDN base URL are both present. The
     * concrete implementation additionally requires resolvable credentials; this binding-level
     * check is the coarse gate the wiring uses to pick the enabled vs disabled implementation
     * (Requirement 7.12).
     *
     * @return {@code true} when both {@link #bucket()} and {@link #cdnBase()} are non-blank
     */
    public boolean isConfigured() {
        return bucket != null && !bucket.isBlank()
                && cdnBase != null && !cdnBase.isBlank();
    }

    /**
     * Google Cloud credentials source. Either {@code path} (an explicit service-account JSON key
     * file path) or {@code location} (a resource location / {@code application-default} sentinel)
     * may be set; both may be empty when storage is not configured.
     *
     * @param path explicit service-account JSON key file path, or {@code null}
     * @param location resource location for credentials (e.g. {@code file:}/{@code classpath:}), or
     *                 {@code null}
     */
    public record Credentials(String path, String location) {
    }

    /**
     * Reconciliation schedule for the periodic orphan-cleanup job (Requirement 7.10). A
     * {@code cron} expression and/or a fixed {@code interval} (an ISO-8601 duration such as
     * {@code PT6H} or a Spring duration string) may be supplied; both may be empty when storage is
     * not configured.
     *
     * @param cron a cron expression for the reconciliation schedule, or {@code null}
     * @param interval a fixed interval between reconciliation runs, or {@code null}
     */
    public record Reconciliation(String cron, String interval) {
    }
}
