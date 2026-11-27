package com.foremen.service.bank.connector.fintable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import java.util.Locale;

/**
 * FOR-07-04a (Requirements 3.2, 3.3, 3.4): pure mapping of Fintable error responses
 * ({@code {"error":{"type","message"}}} + {@code Retry-After}) to {@link FintableConnectorError}.
 *
 * <table>
 *   <tr><th>Response</th><th>class</th><th>message key</th></tr>
 *   <tr><td>401</td><td>CONFIG</td><td>{@code error.bank.fintable.tokenInvalid}</td></tr>
 *   <tr><td>403 on sync / link</td><td>CONFIG</td><td>{@code error.bank.fintable.noPlan}</td></tr>
 *   <tr><td>403 other</td><td>CONFIG</td><td>{@code error.bank.fintable.forbidden}</td></tr>
 *   <tr><td>404 on a connection / account</td><td>REAUTH</td><td>{@code error.bank.provider.reauth}</td></tr>
 *   <tr><td>422 on redirect_url</td><td>CONFIG</td><td>{@code error.bank.fintable.returnHostNotAllowed}</td></tr>
 *   <tr><td>422 connection limit / plan headroom</td><td>LIMIT</td><td>{@code error.bank.fintable.connectionLimit}</td></tr>
 *   <tr><td>422 other</td><td>PROVIDER</td><td>{@code error.bank.provider.unavailable}</td></tr>
 *   <tr><td>429</td><td>LIMIT</td><td>{@code error.bank.fintable.rateLimited}</td></tr>
 *   <tr><td>5xx, timeout, I/O</td><td>TRANSIENT</td><td>{@code error.bank.provider.transient}</td></tr>
 * </table>
 *
 * <p>Any other status (400, 409, other 404, ...) is PROVIDER / {@code error.bank.provider.unavailable}.
 * Messages carry only method, path (no query), status, the provider {@code type} and a truncated
 * provider {@code message}; never request headers or bodies.
 */
public final class FintableErrorMapper {

    public static final String KEY_TOKEN_INVALID = "error.bank.fintable.tokenInvalid";
    public static final String KEY_NO_PLAN = "error.bank.fintable.noPlan";
    public static final String KEY_FORBIDDEN = "error.bank.fintable.forbidden";
    public static final String KEY_RETURN_HOST = "error.bank.fintable.returnHostNotAllowed";
    public static final String KEY_CONNECTION_LIMIT = "error.bank.fintable.connectionLimit";
    public static final String KEY_RATE_LIMITED = "error.bank.fintable.rateLimited";
    public static final String KEY_REAUTH = "error.bank.provider.reauth";
    public static final String KEY_UNAVAILABLE = "error.bank.provider.unavailable";
    public static final String KEY_TRANSIENT = "error.bank.provider.transient";

    static final int MAX_PROVIDER_MESSAGE_LENGTH = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    private FintableErrorMapper() {
    }

    /**
     * Maps a non-2xx response.
     *
     * @param httpStatus       the HTTP status
     * @param method           HTTP method (for the message)
     * @param path             request path without query, e.g. {@code /connections/conn_1/link}
     * @param responseBody     raw body, may be {@code null} / unparsable
     * @param retryAfterHeader raw {@code Retry-After} header value, may be {@code null}
     * @param secretToRedact   a secret (the token) removed from the message if the provider echoed it
     */
    public static FintableConnectorError fromResponse(int httpStatus, String method, String path,
                                                      String responseBody, String retryAfterHeader,
                                                      String secretToRedact) {
        String type = null;
        String providerMessage = null;
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode error = JSON.readTree(responseBody).path("error");
                type = text(error.path("type"));
                providerMessage = text(error.path("message"));
            } catch (Exception ignored) {
                // Unparsable body (HTML from a proxy): classified by status only.
            }
        }
        String lowered = ((type == null ? "" : type) + " " + (providerMessage == null ? "" : providerMessage))
                .toLowerCase(Locale.ROOT);
        String safePath = pathOnly(path);

        ConnectorErrorClass errorClass;
        String key;
        Long retryAfter = null;
        if (httpStatus == 401) {
            errorClass = ConnectorErrorClass.CONFIG;
            key = KEY_TOKEN_INVALID;
        } else if (httpStatus == 403) {
            errorClass = ConnectorErrorClass.CONFIG;
            key = isSyncOrLink(safePath) ? KEY_NO_PLAN : KEY_FORBIDDEN;
        } else if (httpStatus == 404 && isConnectionOrAccount(safePath)) {
            errorClass = ConnectorErrorClass.REAUTH;
            key = KEY_REAUTH;
        } else if (httpStatus == 422) {
            if (lowered.contains("redirect")) {
                errorClass = ConnectorErrorClass.CONFIG;
                key = KEY_RETURN_HOST;
            } else if (lowered.contains("limit") || lowered.contains("headroom") || lowered.contains("plan")) {
                errorClass = ConnectorErrorClass.LIMIT;
                key = KEY_CONNECTION_LIMIT;
            } else {
                errorClass = ConnectorErrorClass.PROVIDER;
                key = KEY_UNAVAILABLE;
            }
        } else if (httpStatus == 429) {
            errorClass = ConnectorErrorClass.LIMIT;
            key = KEY_RATE_LIMITED;
            retryAfter = parseRetryAfter(retryAfterHeader);
        } else if (httpStatus >= 500) {
            errorClass = ConnectorErrorClass.TRANSIENT;
            key = KEY_TRANSIENT;
        } else {
            errorClass = ConnectorErrorClass.PROVIDER;
            key = KEY_UNAVAILABLE;
        }

        StringBuilder message = new StringBuilder("Fintable error ").append(method).append(' ').append(safePath)
                .append(" HTTP ").append(httpStatus);
        if (type != null) {
            message.append(' ').append(sanitize(type, 80));
        }
        String provider = sanitize(providerMessage, MAX_PROVIDER_MESSAGE_LENGTH);
        if (provider != null) {
            message.append(": ").append(provider);
        }
        if (retryAfter != null) {
            message.append(" (retry after ").append(retryAfter).append(" s)");
        }
        return new FintableConnectorError(errorClass, type, key, redact(message.toString(), secretToRedact),
                httpStatus, retryAfter, null);
    }

    /** A network failure / timeout / unreadable response ⇒ TRANSIENT. */
    public static FintableConnectorError transientFailure(String reason, Throwable cause) {
        return new FintableConnectorError(ConnectorErrorClass.TRANSIENT, null, KEY_TRANSIENT,
                "Fintable request failed: " + reason, null, null, cause);
    }

    /** {@code Retry-After} as seconds: delta-seconds only; unparsable / negative ⇒ {@code null}. */
    static Long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? seconds : null;
        } catch (NumberFormatException e) {
            try {
                // Some servers send a decimal.
                double d = Double.parseDouble(header.trim());
                return d >= 0 && !Double.isInfinite(d) ? (long) Math.ceil(d) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static boolean isSyncOrLink(String path) {
        return path.startsWith("/sync") || path.endsWith("/link");
    }

    private static boolean isConnectionOrAccount(String path) {
        return path.startsWith("/connections") || path.startsWith("/accounts");
    }

    private static String pathOnly(String path) {
        if (path == null) {
            return "";
        }
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String redact(String value, String secret) {
        return secret == null || secret.isBlank() ? value : value.replace(secret, "***");
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sanitize(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) + "…" : cleaned;
    }
}
