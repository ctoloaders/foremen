package com.foremen.service.bank.connector.saltedge;

import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.PublicBaseUrl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * FOR-07-04 (Requirement 8.1, 8.3, 24.6; design §Salt Edge connector → Configuration): Salt Edge
 * connector settings bound from {@code foremen.bank-feed.saltedge.*}. Every value is supplied per
 * environment (env vars / Secret Manager); nothing environment-specific is hard-coded here — only
 * the documented defaults.
 *
 * <ul>
 *   <li>{@code mode} — {@link SaltEdgeMode#PARTNER} or {@link SaltEdgeMode#CLIENT} (default CLIENT);</li>
 *   <li>{@code base-url} — blank ⇒ the mode default ({@link #defaultBaseUrl(SaltEdgeMode)});</li>
 *   <li>{@code app-id}, {@code secret} — required when enabled; redacted from {@link #toString()};</li>
 *   <li>{@code private-key-path} — optional PEM; when set every request is signed (R8.5);</li>
 *   <li>{@code customer-identifier} (CLIENT, default {@code foremen-company}) /
 *       {@code lead-email} (PARTNER) — the company's single Salt Edge identity (R8.7);</li>
 *   <li>{@code fetch-days} (89), {@code include-fake-providers} (true), {@code country-codes} (PL),
 *       {@code banks-cache-ttl} (1h), {@code http-timeout} (60s).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "foremen.bank-feed.saltedge")
public record SaltEdgeProperties(
        boolean enabled,
        SaltEdgeMode mode,
        String baseUrl,
        String appId,
        String secret,
        String privateKeyPath,
        String callbackPublicKey,
        String customerIdentifier,
        String leadEmail,
        int fetchDays,
        Boolean includeFakeProviders,
        List<String> countryCodes,
        Duration banksCacheTtl,
        Duration httpTimeout) {

    public static final String PARTNER_DEFAULT_BASE_URL = "https://www.saltedge.com/api/partners/v1";
    public static final String CLIENT_DEFAULT_BASE_URL = "https://www.saltedge.com/api/v6";
    public static final String DEFAULT_CUSTOMER_IDENTIFIER = "foremen-company";
    public static final int DEFAULT_FETCH_DAYS = 89;
    public static final Duration DEFAULT_BANKS_CACHE_TTL = Duration.ofHours(1);
    public static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(60);

    private static final String PREFIX = "foremen.bank-feed.saltedge.";

    @ConstructorBinding
    public SaltEdgeProperties {
        mode = mode != null ? mode : SaltEdgeMode.CLIENT;
        baseUrl = trimToNull(baseUrl);
        appId = trimToNull(appId);
        secret = trimToNull(secret);
        privateKeyPath = trimToNull(privateKeyPath);
        callbackPublicKey = trimToNull(callbackPublicKey);
        customerIdentifier = isBlank(customerIdentifier) ? DEFAULT_CUSTOMER_IDENTIFIER : customerIdentifier.trim();
        leadEmail = trimToNull(leadEmail);
        fetchDays = fetchDays > 0 ? fetchDays : DEFAULT_FETCH_DAYS;
        includeFakeProviders = includeFakeProviders != null ? includeFakeProviders : Boolean.TRUE;
        countryCodes = countryCodes == null || countryCodes.isEmpty() ? List.of("PL") : List.copyOf(countryCodes);
        banksCacheTtl = banksCacheTtl != null ? banksCacheTtl : DEFAULT_BANKS_CACHE_TTL;
        httpTimeout = httpTimeout != null && !httpTimeout.isZero() && !httpTimeout.isNegative()
                ? httpTimeout : DEFAULT_HTTP_TIMEOUT;
    }

    /** The documented base URL for a mode (R8.1). */
    public static String defaultBaseUrl(SaltEdgeMode mode) {
        return mode == SaltEdgeMode.PARTNER ? PARTNER_DEFAULT_BASE_URL : CLIENT_DEFAULT_BASE_URL;
    }

    /** The configured base URL, or the mode default when blank; never ends with {@code /}. */
    public String effectiveBaseUrl() {
        String url = baseUrl != null ? baseUrl : defaultBaseUrl(mode);
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** Request signing is on when a private key path is configured (R8.5). */
    public boolean signingEnabled() {
        return privateKeyPath != null;
    }

    /** {@code include-fake-providers=false} marks the Live environment (design §Environment configuration). */
    public boolean live() {
        return !includeFakeProviders;
    }

    /** The mode-specific identity value: {@code lead-email} for PARTNER, {@code customer-identifier} for CLIENT. */
    public String identity() {
        return mode == SaltEdgeMode.PARTNER ? leadEmail : customerIdentifier;
    }

    /**
     * Configuration problems that make the connector unavailable (R8.2, R8.3). Only property names and
     * reasons are reported, never values. Empty ⇔ the configuration is complete.
     */
    public List<String> configurationProblems(PublicBaseUrl publicBaseUrl) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            problems.add(PREFIX + "enabled is false");
        }
        if (appId == null) {
            problems.add(PREFIX + "app-id is not set");
        }
        if (secret == null) {
            problems.add(PREFIX + "secret is not set");
        }
        if (mode == SaltEdgeMode.PARTNER && leadEmail == null) {
            problems.add(PREFIX + "lead-email is not set (required in PARTNER mode)");
        }
        if (publicBaseUrl == null) {
            problems.add(PublicBaseUrl.PROPERTY + " is not set");
        } else {
            publicBaseUrl.problem(live()).ifPresent(problems::add);
        }
        return List.copyOf(problems);
    }

    /** {@code isAvailable()} per R8.3: enabled, keys, public base URL and mode identity all configured. */
    public boolean isAvailable(PublicBaseUrl publicBaseUrl) {
        return configurationProblems(publicBaseUrl).isEmpty();
    }

    /** Never prints {@code app-id}, {@code secret} or key material (R8.6). */
    @Override
    public String toString() {
        return "SaltEdgeProperties[enabled=" + enabled
                + ", mode=" + mode
                + ", baseUrl=" + effectiveBaseUrl()
                + ", appId=" + (appId != null ? "***" : "<unset>")
                + ", secret=" + (secret != null ? "***" : "<unset>")
                + ", signing=" + signingEnabled()
                + ", customerIdentifier=" + customerIdentifier
                + ", leadEmail=" + (leadEmail != null ? "<set>" : "<unset>")
                + ", fetchDays=" + fetchDays
                + ", includeFakeProviders=" + includeFakeProviders
                + ", countryCodes=" + countryCodes
                + ", banksCacheTtl=" + banksCacheTtl
                + ", httpTimeout=" + httpTimeout + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
