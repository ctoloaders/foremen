package com.foremen.service.bank;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FOR-07-04 (Requirement 8.2, 24.6; design §Environment configuration): the environment's public base
 * URL, read from {@code foremen.public-base-url} ({@code FOREMEN_PUBLIC_BASE_URL}).
 *
 * <p>There is <b>no hard-coded default</b>: every environment sets its own value (e.g. dev =
 * {@code https://dev.foremen.eu}). A trailing slash is stripped. All bank return URLs and Salt Edge
 * callback URLs are derived from it:
 * <ul>
 *   <li>{@link #returnUrl(long)} → {@code {base}/bank/connections/return?connectionId={id}};</li>
 *   <li>{@link #callbackUrl(String)} → {@code {base}/api/bank-feed/saltedge/callbacks/{type}}.</li>
 * </ul>
 *
 * <p>{@link #problem(boolean)} reports why the value cannot be used by the Salt Edge connector:
 * blank, not an absolute http(s) URL, or not HTTPS in Live. The connector is unavailable while a
 * problem exists; the STUB connector only needs {@link #isConfigured()}.
 */
@Component
public class PublicBaseUrl {

    public static final String PROPERTY = "foremen.public-base-url";
    static final String RETURN_PATH = "/bank/connections/return";
    static final String CALLBACK_PATH = "/api/bank-feed/saltedge/callbacks/";

    private static final Pattern CALLBACK_TYPE = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    private final String value;
    private final String invalidReason;

    public PublicBaseUrl(@Value("${" + PROPERTY + ":}") String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.value = normalized;
        this.invalidReason = normalized.isEmpty() ? null : validate(normalized);
    }

    /** {@code true} when a non-blank, well-formed absolute http(s) URL is configured. */
    public boolean isConfigured() {
        return !value.isEmpty() && invalidReason == null;
    }

    /** {@code true} when configured with the {@code https} scheme. */
    public boolean isHttps() {
        return isConfigured() && value.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /** The normalized base URL (no trailing slash), or {@code ""} when not set. */
    public String value() {
        return value;
    }

    /**
     * Why the value cannot be used by the Salt Edge connector, or empty when it can (R8.2).
     *
     * @param live {@code true} in Live ({@code include-fake-providers=false}): HTTPS is mandatory;
     *             HTTP is accepted only in Pending / Test
     */
    public Optional<String> problem(boolean live) {
        if (value.isEmpty()) {
            return Optional.of(PROPERTY + " is not set");
        }
        if (invalidReason != null) {
            return Optional.of(PROPERTY + " " + invalidReason);
        }
        if (live && !isHttps()) {
            return Optional.of(PROPERTY + " must use HTTPS in Live (include-fake-providers=false)");
        }
        return Optional.empty();
    }

    /** {@code {base}/bank/connections/return?connectionId={id}}. */
    public String returnUrl(long connectionId) {
        return requireConfigured() + RETURN_PATH + "?connectionId=" + connectionId;
    }

    /** {@code {base}/api/bank-feed/saltedge/callbacks/{type}} — {@code type} e.g. success / fail / notify / destroy. */
    public String callbackUrl(String type) {
        if (type == null || !CALLBACK_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid callback type");
        }
        return requireConfigured() + CALLBACK_PATH + type;
    }

    @Override
    public String toString() {
        return "PublicBaseUrl[" + (value.isEmpty() ? "<unset>" : value) + "]";
    }

    private String requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(problem(false).orElse(PROPERTY + " is not usable"));
        }
        return value;
    }

    private static String validate(String candidate) {
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "must be an absolute http(s) URL";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "must contain a host";
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return "must not contain a query or fragment";
            }
            return null;
        } catch (URISyntaxException e) {
            return "is not a valid URL";
        }
    }
}
