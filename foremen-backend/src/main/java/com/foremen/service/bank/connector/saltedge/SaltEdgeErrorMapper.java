package com.foremen.service.bank.connector.saltedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * FOR-07-04 (Requirement 12.1, 12.3; design §Error mapping): pure mapping of Salt Edge error
 * responses ({@code {"error":{"class","message","request_id"}}}) and callback {@code error_class}
 * values to typed {@link ConnectorError}s.
 *
 * <table>
 *   <tr><th>Salt Edge {@code error.class}</th><th>class</th><th>message key</th></tr>
 *   <tr><td>credentials / client status / signature</td><td>CONFIG</td><td>{@code error.bank.provider.config}</td></tr>
 *   <tr><td>AIS / partner consent expired, revoked, not found; connection disabled / not found;
 *       invalid credentials</td><td>REAUTH</td><td>{@code error.bank.provider.reauth}</td></tr>
 *   <tr><td>refresh / background-fetch / rate limits</td><td>LIMIT</td><td>{@code error.bank.provider.limit}</td></tr>
 *   <tr><td>(fake) connection limit reached</td><td>LIMIT</td><td>{@code error.bank.provider.connectionLimit}</td></tr>
 *   <tr><td>provider unavailable / inactive / disabled</td><td>PROVIDER</td><td>{@code error.bank.provider.unavailable}</td></tr>
 *   <tr><td>provider keys missing</td><td>PROVIDER</td><td>{@code error.bank.provider.keysRequired}</td></tr>
 *   <tr><td>anything else, 5xx, timeout</td><td>TRANSIENT</td><td>{@code error.bank.provider.transient}</td></tr>
 * </table>
 *
 * <p>{@code ConnectionNotFound} maps to REAUTH; the connector treats it as success on disable.
 * The user-facing text is always the localized key; the raw class + provider message are kept on the
 * error ({@link ConnectorError#getProviderErrorCode()}, {@link ConnectorError#getMessage()}) for
 * support. Messages never include request bodies or secrets.
 */
public final class SaltEdgeErrorMapper {

    public static final String KEY_CONFIG = "error.bank.provider.config";
    public static final String KEY_REAUTH = "error.bank.provider.reauth";
    public static final String KEY_LIMIT = "error.bank.provider.limit";
    public static final String KEY_CONNECTION_LIMIT = "error.bank.provider.connectionLimit";
    public static final String KEY_UNAVAILABLE = "error.bank.provider.unavailable";
    public static final String KEY_KEYS_REQUIRED = "error.bank.provider.keysRequired";
    public static final String KEY_TRANSIENT = "error.bank.provider.transient";

    static final int MAX_PROVIDER_MESSAGE_LENGTH = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Mapping(ConnectorErrorClass errorClass, String messageKey) {
    }

    private static final Map<String, Mapping> MAPPINGS = buildMappings();

    private SaltEdgeErrorMapper() {
    }

    private static Map<String, Mapping> buildMappings() {
        Map<String, Mapping> m = new HashMap<>();
        // CONFIG — design table, plus the other documented header / API-key configuration classes.
        put(m, Set.of("WrongClientCredentials", "ClientDisabled", "ClientPending", "ClientRestricted",
                        "SignatureNotMatch", "ExpiresAtInvalid", "PublicKeyNotProvided", "MissingSignature",
                        "MissingExpiresAt", "ApiKeyNotFound", "AppIdNotProvided", "SecretNotProvided",
                        "ClientNotFound"),
                ConnectorErrorClass.CONFIG, KEY_CONFIG);
        // REAUTH — AIS consent (API v6) + Partners partner-consent equivalents (Partners API v1).
        put(m, Set.of("AispConsentExpired", "AispConsentRevoked", "AispConsentNotFound",
                        "AispConsentAlreadyRevoked", "PartnerConsentExpired", "PartnerConsentRevoked",
                        "PartnerConsentNotFound", "PartnerConsentAlreadyRevoked", "ConnectionDisabled",
                        "InvalidCredentials", "ConnectionNotFound"),
                ConnectorErrorClass.REAUTH, KEY_REAUTH);
        put(m, Set.of("ConnectionCannotBeRefreshed", "BackgroundFetchLimitExceeded", "RateLimitExceeded"),
                ConnectorErrorClass.LIMIT, KEY_LIMIT);
        put(m, Set.of("FakeConnectionsLimitReached", "ConnectionLimitReached"),
                ConnectorErrorClass.LIMIT, KEY_CONNECTION_LIMIT);
        put(m, Set.of("ProviderUnavailable", "ProviderInactive", "ProviderDisabled"),
                ConnectorErrorClass.PROVIDER, KEY_UNAVAILABLE);
        put(m, Set.of("ProviderKeyNotFound", "MissingProviderCredentials"),
                ConnectorErrorClass.PROVIDER, KEY_KEYS_REQUIRED);
        return Map.copyOf(m);
    }

    private static void put(Map<String, Mapping> target, Set<String> classes, ConnectorErrorClass errorClass,
                            String key) {
        classes.forEach(c -> target.put(c, new Mapping(errorClass, key)));
    }

    /** Connector error class for a Salt Edge {@code error.class}; unknown / blank ⇒ TRANSIENT. */
    public static ConnectorErrorClass classify(String saltEdgeErrorClass) {
        return mapping(saltEdgeErrorClass).errorClass();
    }

    /** Localized message key for a Salt Edge {@code error.class}. */
    public static String messageKey(String saltEdgeErrorClass) {
        return mapping(saltEdgeErrorClass).messageKey();
    }

    /** From a callback's {@code error_class} / {@code error_message} (fail callback). */
    public static ConnectorError fromClass(String saltEdgeErrorClass, String providerMessage) {
        return toConnectorError(null, saltEdgeErrorClass, providerMessage, null);
    }

    /**
     * Maps a non-2xx response; a known {@code error.class} wins over the HTTP status, everything else
     * (unknown class, 5xx, unparsable body) is TRANSIENT.
     *
     * @param httpStatus the HTTP status, or {@code null} for callbacks
     */
    public static ConnectorError toConnectorError(Integer httpStatus, String saltEdgeErrorClass,
                                                  String providerMessage, String requestId) {
        String errorClass = blankToNull(saltEdgeErrorClass);
        Mapping mapping = mapping(errorClass);
        StringBuilder message = new StringBuilder("Salt Edge error");
        if (httpStatus != null) {
            message.append(" HTTP ").append(httpStatus);
        }
        message.append(' ').append(errorClass != null ? errorClass : "<no class>");
        String provider = sanitize(providerMessage);
        if (provider != null) {
            message.append(": ").append(provider);
        }
        return new ConnectorError(mapping.errorClass(), errorClass, mapping.messageKey(), message.toString(),
                blankToNull(requestId), null);
    }

    /** Parses a Salt Edge error body and maps it; tolerant of a missing / malformed body. */
    public static ConnectorError fromResponse(int httpStatus, String responseBody) {
        String errorClass = null;
        String message = null;
        String requestId = null;
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                JsonNode error = JSON.readTree(responseBody).path("error");
                errorClass = text(error.path("class"));
                message = text(error.path("message"));
                requestId = text(error.path("request_id"));
            } catch (Exception ignored) {
                // Unparsable error body (e.g. HTML from a proxy) ⇒ classified by status only.
            }
        }
        return toConnectorError(httpStatus, errorClass, message, requestId);
    }

    /** A network failure / timeout / unreadable response ⇒ TRANSIENT. */
    public static ConnectorError transientFailure(String reason, Throwable cause) {
        return new ConnectorError(ConnectorErrorClass.TRANSIENT, null, KEY_TRANSIENT,
                "Salt Edge request failed: " + reason, null, cause);
    }

    private static Mapping mapping(String saltEdgeErrorClass) {
        String key = blankToNull(saltEdgeErrorClass);
        Mapping mapping = key != null ? MAPPINGS.get(key) : null;
        return mapping != null ? mapping : new Mapping(ConnectorErrorClass.TRANSIENT, KEY_TRANSIENT);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : blankToNull(node.asText());
    }

    private static String sanitize(String providerMessage) {
        String value = blankToNull(providerMessage);
        if (value == null) {
            return null;
        }
        value = value.replaceAll("[\\r\\n\\t]+", " ");
        return value.length() > MAX_PROVIDER_MESSAGE_LENGTH
                ? value.substring(0, MAX_PROVIDER_MESSAGE_LENGTH) + "…" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
