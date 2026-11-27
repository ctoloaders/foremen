package com.foremen.service.bank.connector.fintable;

import com.foremen.service.bank.PublicBaseUrl;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * FOR-07-04a (Requirements 2.1, 2.2, 2.4): Fintable connector settings bound from
 * {@code foremen.bank-feed.fintable.*}.
 *
 * <p>The personal access token comes from {@code api-token} (env {@code FINTABLE_API_TOKEN}; on the VM it
 * is written by {@code vm-startup.sh} from Google Secret Manager, locally from {@code .secrets/fintable.env}).
 * It is redacted from {@link #toString()}. {@link #problems()} reports property <b>names</b> only, never values.
 */
@ConfigurationProperties(prefix = "foremen.bank-feed.fintable")
public record FintableProperties(
        boolean enabled,
        String baseUrl,
        boolean allowInsecureBaseUrl,
        String apiToken,
        LocalDate tokenExpiresOn,
        String workspaceId,
        Duration httpTimeout,
        List<String> countryCodes,
        List<String> providerPreference,
        List<String> popularBanks,
        Duration syncWait,
        Duration syncPollInterval,
        Duration manualSyncInterval,
        Duration cursorOverlap,
        Integer tokenWarnDays) {

    public static final String DEFAULT_BASE_URL = "https://fintable.io/api/v2";
    public static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SYNC_WAIT = Duration.ofMinutes(10);
    public static final Duration DEFAULT_SYNC_POLL_INTERVAL = Duration.ofSeconds(15);
    public static final Duration DEFAULT_MANUAL_SYNC_INTERVAL = Duration.ofHours(12);
    public static final Duration DEFAULT_CURSOR_OVERLAP = Duration.ofHours(1);
    public static final int DEFAULT_TOKEN_WARN_DAYS = 30;

    private static final String PREFIX = "foremen.bank-feed.fintable.";

    @ConstructorBinding
    public FintableProperties {
        baseUrl = isBlank(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim();
        apiToken = trimToNull(apiToken);
        workspaceId = trimToNull(workspaceId);
        httpTimeout = positive(httpTimeout, DEFAULT_HTTP_TIMEOUT);
        countryCodes = nonEmpty(countryCodes, List.of("PL"));
        providerPreference = nonEmpty(providerPreference, List.of("ENABLEBANKING", "NORDIGEN"));
        popularBanks = nonEmpty(popularBanks,
                List.of("PKO", "mBank", "ING", "Pekao", "Santander", "Millennium", "Alior"));
        syncWait = positive(syncWait, DEFAULT_SYNC_WAIT);
        syncPollInterval = positive(syncPollInterval, DEFAULT_SYNC_POLL_INTERVAL);
        manualSyncInterval = positive(manualSyncInterval, DEFAULT_MANUAL_SYNC_INTERVAL);
        cursorOverlap = cursorOverlap != null && !cursorOverlap.isNegative() ? cursorOverlap : DEFAULT_CURSOR_OVERLAP;
        tokenWarnDays = tokenWarnDays != null && tokenWarnDays > 0 ? tokenWarnDays : DEFAULT_TOKEN_WARN_DAYS;
    }


    /** The base URL without a trailing slash. */
    public String effectiveBaseUrl() {
        String url = baseUrl;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Names (with a short reason) of the missing or invalid properties, never values. Does not include
     * the {@code enabled} flag or the public base URL; see {@link #isAvailable(PublicBaseUrl)}.
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (apiToken == null) {
            problems.add(PREFIX + "api-token is not set");
        }
        String urlProblem = baseUrlProblem();
        if (urlProblem != null) {
            problems.add(PREFIX + "base-url " + urlProblem);
        }
        return List.copyOf(problems);
    }

    /** All problems including the public base URL; empty when the connector can be used. */
    public List<String> availabilityProblems(PublicBaseUrl publicBaseUrl) {
        List<String> all = new ArrayList<>(problems());
        if (publicBaseUrl == null || !publicBaseUrl.isConfigured()) {
            all.add(PublicBaseUrl.PROPERTY + " is not set");
        }
        return List.copyOf(all);
    }

    /** Requirement 2.2: enabled, token configured, valid base URL and a public base URL. */
    public boolean isAvailable(PublicBaseUrl publicBaseUrl) {
        return enabled && problems().isEmpty() && publicBaseUrl != null && publicBaseUrl.isConfigured();
    }

    private String baseUrlProblem() {
        try {
            URI uri = new URI(effectiveBaseUrl());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "must be an absolute URL with a host";
            }
            if (scheme.equals("https")) {
                return null;
            }
            if (scheme.equals("http")) {
                return allowInsecureBaseUrl ? null : "must use HTTPS";
            }
            return "must be an absolute HTTPS URL";
        } catch (URISyntaxException e) {
            return "is not a valid URL";
        }
    }

    /** Never prints the token or workspace id. */
    @Override
    public String toString() {
        return "FintableProperties[enabled=" + enabled
                + ", baseUrl=" + effectiveBaseUrl()
                + ", apiToken=" + (apiToken != null ? "***" : "<unset>")
                + ", tokenExpiresOn=" + tokenExpiresOn
                + ", workspaceId=" + (workspaceId != null ? "<set>" : "<unset>")
                + ", httpTimeout=" + httpTimeout
                + ", countryCodes=" + countryCodes
                + ", syncWait=" + syncWait
                + ", manualSyncInterval=" + manualSyncInterval + "]";
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value != null && !value.isZero() && !value.isNegative() ? value : fallback;
    }

    private static List<String> nonEmpty(List<String> value, List<String> fallback) {
        if (value == null) {
            return fallback;
        }
        List<String> cleaned = value.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
