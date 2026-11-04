package com.foremen.service.bank.connector.saltedge;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * FOR-07-04 (Requirements 8.7, 9.1, 9.4, 9.5, 9.7, 9.8, 9.9; design §Salt Edge connector): the internal
 * port over the two Salt Edge access modes. {@code SaltEdgeConnector} depends only on this interface,
 * so switching {@code foremen.bank-feed.saltedge.mode} is a configuration change (R9.9).
 *
 * <ul>
 *   <li>{@link ClientModeApi} — API v6 ({@code /api/v6}): customer, {@code /connections/connect|reconnect|refresh},
 *       AIS consent revoke + {@code DELETE /connections/{id}};</li>
 *   <li>{@link PartnerModeApi} — Partners API v1 ({@code /api/partners/v1}): lead, {@code /lead_sessions/*},
 *       partner-consent revoke.</li>
 * </ul>
 *
 * <p>Implementations return raw Salt Edge JSON ({@code data} objects / arrays) or thin records; mapping
 * to the connector SPI stays in {@link SaltEdgeMapper} and the connector. Request bodies are built from
 * the mode-neutral {@link SessionRequest}, so the connector never deals with per-mode field names
 * (v6 {@code consent.scopes=[accounts,transactions,holder_info]} vs Partners
 * {@code [account_details,transactions_details,holder_information]}, v6 {@code attempt.fetch_from_date} vs
 * Partners {@code attempt.from_date}, v6 {@code provider{code}} / {@code widget{skip_provider_selection}} vs
 * Partners top-level {@code provider_code} / {@code skip_provider_select}).
 *
 * <p>Routes and field names are pinned against the API references and recorded in the test fixtures
 * {@code src/test/resources/saltedge/client/routes.json} and {@code saltedge/partner/routes.json}.
 */
public interface SaltEdgeModeApi {

    /** Salt Edge error classes that mean "already gone" when disabling a connection (R9.8). */
    List<String> ALREADY_GONE_CLASSES = List.of(
            "ConnectionNotFound",
            "AispConsentNotFound", "AispConsentRevoked", "AispConsentAlreadyRevoked", "AispConsentExpired",
            "PartnerConsentNotFound", "PartnerConsentRevoked", "PartnerConsentAlreadyRevoked",
            "PartnerConsentExpired");

    /** The mode this implementation talks to; recorded on new connections as {@code provider_mode}. */
    SaltEdgeMode mode();

    // ---------------------------------------------------------------- identity (R8.7)

    /**
     * The company's single Salt Edge identity → {@code customer_id}.
     * <ul>
     *   <li>CLIENT — the customer with {@code identifier = customer-identifier} (looked up, else created;
     *       a {@code DuplicatedCustomer} race is resolved by looking it up again);</li>
     *   <li>PARTNER — {@code POST /leads {email: lead-email}} (create-or-return).</li>
     * </ul>
     * Callers cache the result / reuse the id stored on existing connections.
     */
    String ensureIdentity();

    // ---------------------------------------------------------------- sessions (R9.1, R9.4, R9.5)

    /** Opens a connect session (CLIENT {@code POST /connections/connect}; PARTNER {@code POST /lead_sessions/create}). */
    SessionUrl createSession(SessionRequest request);

    /** Reconnect with a fresh consent (CLIENT {@code POST /connections/{id}/reconnect}; PARTNER {@code POST /lead_sessions/reconnect}). */
    SessionUrl reconnectSession(String connectionId, SessionRequest request);

    /** Interactive refresh (CLIENT {@code POST /connections/{id}/refresh}; PARTNER {@code POST /lead_sessions/refresh}). */
    SessionUrl refreshSession(String connectionId, SessionRequest request);

    /** Whether the mode offers a background (no-widget) refresh — CLIENT only. */
    boolean supportsBackgroundRefresh();

    /**
     * Background refresh (CLIENT {@code POST /connections/{id}/background_refresh}) → the connection
     * {@code data}. Never called by the scheduled pull (R9.6).
     *
     * @throws UnsupportedOperationException when {@link #supportsBackgroundRefresh()} is false
     */
    JsonNode backgroundRefresh(String connectionId, SessionRequest request);

    // ---------------------------------------------------------------- connection + consent (R9.7)

    /** {@code GET /connections/{id}} → {@code data} (status, next_refresh_possible_at, last_consent_id, …). */
    JsonNode connection(String connectionId);

    /**
     * The latest AIS consent (and in PARTNER mode the latest partner consent) of a connection.
     *
     * @param connection the connection {@code data} as returned by {@link #connection(String)}
     */
    ConsentSnapshot consents(JsonNode connection);

    /**
     * Holder information of a connection, or empty when the bank did not return any.
     * CLIENT — the connection's {@code holder_info} attribute; PARTNER — {@code GET /holder_info?connection_id=}.
     *
     * @param connection the connection {@code data} as returned by {@link #connection(String)}
     */
    Optional<JsonNode> holderInfo(JsonNode connection);

    // ---------------------------------------------------------------- data reads

    /** {@code GET /accounts?connection_id=} (all pages). */
    List<JsonNode> accounts(String connectionId);

    /** One page of posted transactions: {@code GET /transactions?connection_id&account_id&from_id}. */
    SaltEdgeClient.Page transactions(String connectionId, String accountId, String fromId);

    /**
     * All pending transactions of an account (replaced on every refresh).
     * CLIENT — {@code GET /transactions?connection_id&account_id&pending=true};
     * PARTNER — {@code GET /transactions/pending?connection_id&account_id}.
     */
    List<JsonNode> pendingTransactions(String connectionId, String accountId);

    /** Supported providers of a country (all pages), with the AIS capability fields (R9.2). */
    List<JsonNode> providers(String countryCode, boolean includeFake);

    /**
     * CLIENT — the providers usable with Salt Edge's own keys ({@code key_owner=saltedge}); banks absent
     * from it need own client keys. PARTNER — empty (never requires own keys).
     */
    Optional<List<JsonNode>> providersWithSaltEdgeKeys(String countryCode, boolean includeFake);

    // ---------------------------------------------------------------- revoke / delete (R9.8)

    /**
     * Revokes the consent and removes the connection at Salt Edge where the mode allows it:
     * CLIENT — AIS consent revoke ({@code PUT /consents/{id}/revoke}) + {@code DELETE /connections/{id}};
     * PARTNER — partner consent revoke ({@code PUT /partner_consents/{id}/revoke}); the Partners API has no
     * connection delete route. "Already gone" responses ({@link #ALREADY_GONE_CLASSES}) count as success.
     */
    void revokeAndRemove(String connectionId);

    // ---------------------------------------------------------------- factory + shared records

    /** The implementation for the client's configured mode. */
    static SaltEdgeModeApi forMode(SaltEdgeClient client) {
        Objects.requireNonNull(client, "client");
        return client.properties().mode() == SaltEdgeMode.PARTNER
                ? new PartnerModeApi(client)
                : new ClientModeApi(client);
    }

    /**
     * Mode-neutral session parameters (R9.1).
     *
     * @param customerId           Salt Edge customer id (CLIENT customer / PARTNER lead customer)
     * @param providerCode         bank code; {@code null} ⇒ the widget shows provider selection
     * @param holderInfo           request the holder-info consent / fetch scope (bank supports it)
     * @param fromDate             consent / fetch start (today − fetch-days)
     * @param returnTo             {@code {Public_Base_URL}/bank/connections/return?connectionId={id}}
     * @param customFields         echoed on callbacks, e.g. {@code foremenConnectionId}
     * @param includeFakeProviders {@code include-fake-providers}
     */
    record SessionRequest(String customerId, String providerCode, boolean holderInfo, LocalDate fromDate,
                          String returnTo, Map<String, String> customFields, boolean includeFakeProviders) {
        public SessionRequest {
            customFields = customFields == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(customFields));
        }
    }

    /** The widget URL of a session (v6 {@code connect_url}, Partners {@code redirect_url}) and its expiry. */
    record SessionUrl(String url, Instant expiresAt) {
    }

    /**
     * The latest consents of a connection; either may be {@code null} when none is visible.
     *
     * @param consent        the latest AIS consent {@code data} (status, expires_at, revoked_at, …)
     * @param partnerConsent PARTNER only — the latest partner consent {@code data} (status, revoked_at, …)
     */
    record ConsentSnapshot(JsonNode consent, JsonNode partnerConsent) {
    }

    // ---------------------------------------------------------------- helpers for implementations

    /** {@code root.data}, or a TRANSIENT error when the response has no {@code data} object. */
    static JsonNode dataObject(JsonNode root, String what) {
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isObject()) {
            throw SaltEdgeErrorMapper.transientFailure("response of " + what + " has no data object", null);
        }
        return data;
    }

    /** Text value or {@code null} for missing / null / blank. */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String s = value.asText();
        return s.isBlank() ? null : s;
    }

    /** ISO instant or {@code null}. */
    static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** True when the error says the resource is already revoked / removed (R9.8). */
    static boolean alreadyGone(ConnectorError error) {
        return error.getProviderErrorCode() != null && ALREADY_GONE_CLASSES.contains(error.getProviderErrorCode());
    }

    /** CONFIG error for a missing mode identity property. */
    static ConnectorError missingIdentity(String property) {
        return new ConnectorError(ConnectorErrorClass.CONFIG, null, SaltEdgeErrorMapper.KEY_CONFIG,
                "foremen.bank-feed.saltedge." + property + " is not set", null, null);
    }
}
