package com.foremen.service.bank.connector.saltedge;

import static com.foremen.service.bank.connector.saltedge.SaltEdgeModeApi.instant;
import static com.foremen.service.bank.connector.saltedge.SaltEdgeModeApi.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectionStatusHint;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.CredentialField;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.connector.NormalizedAccountRef;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.RefreshMode;
import com.foremen.service.bank.connector.RefreshResult;
import com.foremen.service.bank.connector.TransactionPage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FOR-07-04 (Requirements 8.3, 8.7, 9.1–9.9, 11.2, 11.5, 12.4, 12.6; design §Salt Edge connector): the
 * {@link FeedProvider#SALT_EDGE} {@link BankFeedConnector}.
 *
 * <p>All Salt Edge calls go through the mode port {@link SaltEdgeModeApi} (CLIENT API v6 or PARTNER
 * Partners API v1, selected by {@code foremen.bank-feed.saltedge.mode}), so switching the mode is a
 * configuration change (R9.9). The bean always exists; with incomplete configuration it only reports
 * {@link #isAvailable()} {@code = false} (R8.3).
 *
 * <ul>
 *   <li><b>Identity</b> (R8.7) — the customer / lead id stored on the connection is reused; otherwise
 *       {@link SaltEdgeModeApi#ensureIdentity()} is called once and cached.</li>
 *   <li><b>Sessions</b> (R9.1, R9.4) — {@code return_to} = {@link PublicBaseUrl#returnUrl(long)},
 *       {@code custom_fields.foremenConnectionId}, consent / fetch window today − {@code fetch-days};
 *       the holder scope is requested only when the bank's {@code supported_fetch_scopes} include it.</li>
 *   <li><b>Refresh</b> (R9.5, R9.6) — reads the connection first; a future
 *       {@code next_refresh_possible_at} yields {@link RefreshResult#notYet} without a refresh call.
 *       {@link RefreshMode#BACKGROUND} is never used by the scheduled pull.</li>
 *   <li><b>Transactions</b> (R11.2, R11.5) — posted rows page by {@code from_id}; the pending snapshot
 *       is returned with the first page of a fetch only (see {@link #fetchTransactions}). Only data
 *       Salt Edge already holds is read — no implicit refresh.</li>
 *   <li><b>Mode</b> — a connection keeps the {@code provider_mode} it was created in; provider calls for
 *       a connection of the other mode fail with {@link ConnectorErrorClass#CONFIG} (the bank must be
 *       added again under the current mode, R9.9).</li>
 * </ul>
 */
public class SaltEdgeConnector implements BankFeedConnector {

    private static final Logger log = LoggerFactory.getLogger(SaltEdgeConnector.class);

    /** Custom field echoed by Salt Edge on callbacks to resolve the Foremen connection (R9.3, R10.4). */
    public static final String CUSTOM_FIELD_CONNECTION_ID = "foremenConnectionId";
    /** Prefix of a continuation cursor (a page inside one fetch); see {@link #fetchTransactions}. */
    static final String CONTINUATION_PREFIX = "next:";
    static final int DEFAULT_WARN_DAYS = 14;

    /** Return-URL / callback parameters that may carry the Salt Edge connection id (R9.3). */
    static final List<String> CONNECTION_ID_PARAMS = List.of("connection_id", "externalConnectionId", "connectionId");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SaltEdgeModeApi api;
    private final SaltEdgeProperties properties;
    private final PublicBaseUrl publicBaseUrl;
    private final Clock clock;
    private final Duration warnBefore;

    private volatile String cachedCustomerId;
    /** Providers seen in listings, by code — used to decide the holder scope. */
    private final Map<String, ProviderBank> knownProviders = new ConcurrentHashMap<>();

    public SaltEdgeConnector(SaltEdgeModeApi api, SaltEdgeProperties properties, PublicBaseUrl publicBaseUrl,
                             Clock clock, int consentWarnDays) {
        this.api = Objects.requireNonNull(api, "api");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.warnBefore = Duration.ofDays(consentWarnDays > 0 ? consentWarnDays : DEFAULT_WARN_DAYS);
    }

    @Override
    public FeedProvider provider() {
        return FeedProvider.SALT_EDGE;
    }

    /** Enabled, {@code app-id} + {@code secret}, a usable {@code Public_Base_URL} and the mode identity (R8.2, R8.3). */
    @Override
    public boolean isAvailable() {
        return properties.isAvailable(publicBaseUrl);
    }

    /** The configured mode; recorded on new connections as {@code provider_mode}. */
    public SaltEdgeMode mode() {
        return api.mode();
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of();
    }

    // ------------------------------------------------------------------ identity (R8.7)

    /** The company's single Salt Edge customer / lead id; resolved once and cached. */
    public String ensureIdentity() {
        String cached = cachedCustomerId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedCustomerId == null) {
                cachedCustomerId = api.ensureIdentity();
            }
            return cachedCustomerId;
        }
    }

    private String customerId(BankConnectionRef connection) {
        String stored = connection.externalCustomerId();
        if (stored != null && !stored.isBlank()) {
            if (cachedCustomerId == null) {
                cachedCustomerId = stored.trim();
            }
            return stored.trim();
        }
        return ensureIdentity();
    }

    // ------------------------------------------------------------------ banks (R9.2, R12.6)

    /**
     * Live provider catalogue of {@code countryCode} (default: the configured countries), plus the fake
     * {@code XF} providers when {@code include-fake-providers = true}. In CLIENT mode a bank absent from
     * the {@code key_owner=saltedge} listing is flagged {@code requiresOwnKeys}.
     */
    @Override
    public List<ProviderBank> listBanks(String countryCode) {
        boolean includeFake = Boolean.TRUE.equals(properties.includeFakeProviders());
        Set<String> countries = new LinkedHashSet<>();
        if (countryCode != null && !countryCode.isBlank()) {
            countries.add(countryCode.trim().toUpperCase(Locale.ROOT));
        } else {
            properties.countryCodes().forEach(c -> countries.add(c.trim().toUpperCase(Locale.ROOT)));
        }
        if (includeFake) {
            countries.add(SaltEdgeMapper.FAKE_COUNTRY);
        } else {
            countries.remove(SaltEdgeMapper.FAKE_COUNTRY);
        }
        Map<String, ProviderBank> merged = new LinkedHashMap<>();
        for (String country : countries) {
            for (ProviderBank bank : providersOf(country, includeFake)) {
                merged.putIfAbsent(bank.code(), bank);
            }
        }
        return List.copyOf(merged.values());
    }

    private List<ProviderBank> providersOf(String country, boolean includeFake) {
        List<JsonNode> rows = api.providers(country, includeFake);
        Set<String> saltEdgeKeyCodes = api.providersWithSaltEdgeKeys(country, includeFake)
                .map(list -> SaltEdgeMapper.providerCodes(array(list)))
                .orElse(null);
        SaltEdgeMapper.Mapped<ProviderBank> mapped = SaltEdgeMapper.toProviderBanks(array(rows), saltEdgeKeyCodes);
        if (mapped.skippedMalformed() > 0) {
            log.warn("Salt Edge providers {}: skipped {} malformed rows", country, mapped.skippedMalformed());
        }
        mapped.rows().forEach(b -> knownProviders.put(b.code(), b));
        return mapped.rows();
    }

    /** Whether the bank's {@code supported_fetch_scopes} include holder info; unknown ⇒ {@code false}. */
    boolean supportsHolderInfo(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return false;
        }
        ProviderBank bank = knownProviders.get(providerCode);
        if (bank == null) {
            try {
                listBanks(providerCode.toLowerCase(Locale.ROOT).endsWith("_xf") ? SaltEdgeMapper.FAKE_COUNTRY : null);
            } catch (ConnectorError e) {
                log.warn("Salt Edge provider lookup for {} failed ({}); holder scope not requested",
                        providerCode, e.getErrorClass());
                return false;
            }
            bank = knownProviders.get(providerCode);
        }
        return bank != null && hasHolderScope(bank.fetchScopes());
    }

    static boolean hasHolderScope(Set<String> fetchScopes) {
        return fetchScopes.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).startsWith("holder"));
    }

    // ------------------------------------------------------------------ sessions (R9.1, R9.3, R9.4)

    @Override
    public AuthorizationStart startAuthorization(BankConnectionRef connection, String providerBankCode, URI returnUrl) {
        requireCurrentMode(connection);
        String customerId = customerId(connection);
        String bankCode = providerBankCode != null && !providerBankCode.isBlank()
                ? providerBankCode.trim() : connection.providerCode();
        SaltEdgeModeApi.SessionRequest request = sessionRequest(connection, customerId, bankCode, returnUrl);
        SaltEdgeModeApi.SessionUrl session = api.createSession(request);
        return new AuthorizationStart(URI.create(session.url()), session.expiresAt(), customerId);
    }

    /**
     * Completes authorization from the {@code return_to} parameters or the success callback (R9.3).
     * Idempotent: it only reads the connection. Without a known Salt Edge connection id yet (the
     * callback has not arrived) the state stays {@link ConnectionStatusHint#PENDING_AUTH}.
     */
    @Override
    public ConnectionState completeAuthorization(BankConnectionRef connection, Map<String, String> returnParams) {
        requireCurrentMode(connection);
        Map<String, String> params = returnParams == null ? Map.of() : returnParams;
        String errorClass = blankToNull(params.get("error_class"));
        if (errorClass != null) {
            throw SaltEdgeErrorMapper.fromClass(errorClass, params.get("error_message"));
        }
        String externalId = null;
        for (String key : CONNECTION_ID_PARAMS) {
            externalId = blankToNull(params.get(key));
            if (externalId != null) {
                break;
            }
        }
        if (externalId == null) {
            externalId = blankToNull(connection.externalConnectionId());
        }
        if (externalId == null) {
            return new ConnectionState(ConnectionStatusHint.PENDING_AUTH, null, null, null, null,
                    connection.providerCode(), null, null);
        }
        return stateOf(api.connection(externalId));
    }

    /** Fresh consent through the mode's reconnect endpoint; the Salt Edge connection id is kept (R9.4). */
    @Override
    public AuthorizationStart startReconnect(BankConnectionRef connection, URI returnUrl) {
        requireCurrentMode(connection);
        String externalId = blankToNull(connection.externalConnectionId());
        if (externalId == null) {
            // Never authorized at Salt Edge: a new connect session is the only way to (re)authorize.
            return startAuthorization(connection, connection.providerCode(), returnUrl);
        }
        String customerId = customerId(connection);
        SaltEdgeModeApi.SessionUrl session = api.reconnectSession(externalId,
                sessionRequest(connection, customerId, connection.providerCode(), returnUrl));
        return new AuthorizationStart(URI.create(session.url()), session.expiresAt(), customerId);
    }

    /**
     * Reads the connection; when {@code next_refresh_possible_at} is in the future returns "not yet"
     * without calling refresh (R9.5). INTERACTIVE opens the refresh widget; BACKGROUND (CLIENT only,
     * never used by the scheduler — R9.6) asks Salt Edge to refresh without the user.
     */
    @Override
    public RefreshResult requestRefresh(BankConnectionRef connection, RefreshMode mode) {
        requireCurrentMode(connection);
        String externalId = requireExternalId(connection);
        JsonNode current = api.connection(externalId);
        Instant nextPossible = instant(current, "next_refresh_possible_at");
        if (nextPossible != null && nextPossible.isAfter(clock.instant())) {
            return RefreshResult.notYet(nextPossible);
        }
        SaltEdgeModeApi.SessionRequest request = sessionRequest(connection, customerId(connection),
                connection.providerCode(), null);
        if (mode == RefreshMode.BACKGROUND) {
            if (!api.supportsBackgroundRefresh()) {
                // PARTNER: no background refresh; Salt Edge daily_refresh applies.
                return RefreshResult.notYet(nextPossible);
            }
            JsonNode refreshed = api.backgroundRefresh(externalId, request);
            return RefreshResult.started(instant(refreshed, "next_refresh_possible_at"), null);
        }
        SaltEdgeModeApi.SessionUrl session = api.refreshSession(externalId, request);
        return RefreshResult.started(null, URI.create(session.url()));
    }

    private SaltEdgeModeApi.SessionRequest sessionRequest(BankConnectionRef connection, String customerId,
                                                          String providerCode, URI fallbackReturnUrl) {
        Map<String, String> customFields = new LinkedHashMap<>();
        if (connection.id() != null) {
            customFields.put(CUSTOM_FIELD_CONNECTION_ID, connection.id().toString());
        }
        return new SaltEdgeModeApi.SessionRequest(
                customerId,
                providerCode,
                supportsHolderInfo(providerCode),
                fromDate(),
                returnTo(connection, fallbackReturnUrl),
                customFields,
                Boolean.TRUE.equals(properties.includeFakeProviders()));
    }

    /** today − {@code fetch-days} (consent {@code from_date} / {@code fetch_from_date}). */
    LocalDate fromDate() {
        return LocalDate.now(clock).minusDays(properties.fetchDays());
    }

    /** {@code {Public_Base_URL}/bank/connections/return?connectionId={id}}; the caller's URL only as fallback. */
    private String returnTo(BankConnectionRef connection, URI fallback) {
        if (connection.id() != null && publicBaseUrl.isConfigured()) {
            return publicBaseUrl.returnUrl(connection.id());
        }
        return fallback == null ? null : fallback.toString();
    }

    // ------------------------------------------------------------------ state (R9.7)

    @Override
    public ConnectionState fetchConnectionState(BankConnectionRef connection) {
        requireCurrentMode(connection);
        String externalId = blankToNull(connection.externalConnectionId());
        if (externalId == null) {
            return new ConnectionState(ConnectionStatusHint.PENDING_AUTH, null, null, null, null,
                    connection.providerCode(), null, null);
        }
        return stateOf(api.connection(externalId));
    }

    private ConnectionState stateOf(JsonNode conn) {
        SaltEdgeModeApi.ConsentSnapshot consents = api.consents(conn);
        JsonNode consent = consents.consent();
        Instant expiresAt = instant(consent, "expires_at");
        String consentId = text(consent, "id");
        if (consentId == null) {
            consentId = text(conn, "last_consent_id");
        }
        return new ConnectionState(
                statusOf(conn, consent, consents.partnerConsent(), clock.instant(), warnBefore),
                text(conn, "id"),
                consentId,
                expiresAt,
                instant(conn, "next_refresh_possible_at"),
                text(conn, "provider_code"),
                text(conn, "provider_name"),
                null);
    }

    /**
     * {@code disabled → DISABLED}; {@code inactive}, consent expired / revoked (AIS or partner) →
     * {@code REAUTH_REQUIRED}; {@code active} → {@code CONSENT_EXPIRING} within {@code warn-days} of the
     * consent expiry, else {@code ACTIVE}.
     */
    static ConnectionStatusHint statusOf(JsonNode conn, JsonNode consent, JsonNode partnerConsent,
                                         Instant now, Duration warnBefore) {
        String status = Optional.ofNullable(text(conn, "status")).map(s -> s.toLowerCase(Locale.ROOT)).orElse("");
        if ("disabled".equals(status)) {
            return ConnectionStatusHint.DISABLED;
        }
        if ("inactive".equals(status) || isGone(consent, now) || isGone(partnerConsent, now)) {
            return ConnectionStatusHint.REAUTH_REQUIRED;
        }
        Instant expiresAt = instant(consent, "expires_at");
        if (expiresAt != null && !expiresAt.isAfter(now.plus(warnBefore))) {
            return ConnectionStatusHint.CONSENT_EXPIRING;
        }
        return ConnectionStatusHint.ACTIVE;
    }

    private static boolean isGone(JsonNode consent, Instant now) {
        if (consent == null || consent.isNull() || consent.isMissingNode()) {
            return false;
        }
        if (text(consent, "revoked_at") != null) {
            return true;
        }
        String status = text(consent, "status");
        if (status != null && Set.of("revoked", "expired").contains(status.toLowerCase(Locale.ROOT))) {
            return true;
        }
        Instant expiresAt = instant(consent, "expires_at");
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    // ------------------------------------------------------------------ data (R3.7, R11)

    @Override
    public Optional<HolderInfo> fetchHolderInfo(BankConnectionRef connection) {
        requireCurrentMode(connection);
        String externalId = blankToNull(connection.externalConnectionId());
        if (externalId == null) {
            return Optional.empty();
        }
        return api.holderInfo(api.connection(externalId))
                .map(SaltEdgeMapper::toHolderInfo)
                .filter(h -> !h.names().isEmpty() || !h.taxIds().isEmpty());
    }

    @Override
    public List<NormalizedAccount> fetchAccounts(BankConnectionRef connection) {
        requireCurrentMode(connection);
        SaltEdgeMapper.Mapped<NormalizedAccount> mapped =
                SaltEdgeMapper.toAccounts(array(api.accounts(requireExternalId(connection))));
        if (mapped.skippedMalformed() > 0) {
            log.warn("Salt Edge accounts of connection {}: skipped {} malformed rows",
                    connection.id(), mapped.skippedMalformed());
        }
        return mapped.rows();
    }

    /**
     * One page of transactions of an account (R11.2). Cursor contract (opaque to callers):
     * <ul>
     *   <li>{@code null} or a resume cursor (the max posted id stored on the account) starts a fetch:
     *       posted rows from that id ({@code from_id}, inclusive — ingest dedupes) plus the full pending
     *       snapshot of the account;</li>
     *   <li>a returned {@code nextCursor} ({@value #CONTINUATION_PREFIX}{@code <next_id>}) continues the
     *       same fetch: posted rows only;</li>
     *   <li>{@code nextCursor = null} / {@code complete = true} on the last page.</li>
     * </ul>
     */
    @Override
    public TransactionPage fetchTransactions(BankConnectionRef connection, NormalizedAccountRef account, String cursor) {
        requireCurrentMode(connection);
        String externalId = requireExternalId(connection);
        Objects.requireNonNull(account, "account");
        String accountId = account.externalId();
        boolean continuation = cursor != null && cursor.startsWith(CONTINUATION_PREFIX);
        String fromId = continuation ? blankToNull(cursor.substring(CONTINUATION_PREFIX.length())) : blankToNull(cursor);

        SaltEdgeClient.Page page = api.transactions(externalId, accountId, fromId);
        List<JsonNode> pending = continuation ? List.of() : api.pendingTransactions(externalId, accountId);
        String nextId = blankToNull(page.nextId());
        String nextCursor = nextId == null ? null : CONTINUATION_PREFIX + nextId;
        return SaltEdgeMapper.toTransactionPage(array(page.data()), array(pending), nextCursor, nextCursor == null);
    }

    // ------------------------------------------------------------------ disable (R9.8)

    /** Mode-specific consent revoke (+ connection delete in CLIENT); not-found / already revoked = success. */
    @Override
    public void disable(BankConnectionRef connection) {
        requireCurrentMode(connection);
        String externalId = blankToNull(connection.externalConnectionId());
        if (externalId == null) {
            return; // nothing was created at Salt Edge
        }
        try {
            api.revokeAndRemove(externalId);
        } catch (ConnectorError e) {
            if (!SaltEdgeModeApi.alreadyGone(e)) {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireCurrentMode(BankConnectionRef connection) {
        Objects.requireNonNull(connection, "connection");
        SaltEdgeMode connectionMode = connection.providerMode();
        if (connectionMode != null && connectionMode != api.mode()) {
            throw new ConnectorError(ConnectorErrorClass.CONFIG, null, SaltEdgeErrorMapper.KEY_CONFIG,
                    "Connection " + connection.id() + " was created in Salt Edge " + connectionMode
                            + " mode but the connector runs in " + api.mode() + " mode; add the bank again");
        }
    }

    private static String requireExternalId(BankConnectionRef connection) {
        String externalId = blankToNull(connection.externalConnectionId());
        if (externalId == null) {
            throw new ConnectorError(ConnectorErrorClass.REAUTH, null, SaltEdgeErrorMapper.KEY_REAUTH,
                    "Connection " + connection.id() + " has no Salt Edge connection id yet");
        }
        return externalId;
    }

    private static ArrayNode array(List<JsonNode> rows) {
        ArrayNode array = NODES.arrayNode();
        if (rows != null) {
            rows.forEach(array::add);
        }
        return array;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
