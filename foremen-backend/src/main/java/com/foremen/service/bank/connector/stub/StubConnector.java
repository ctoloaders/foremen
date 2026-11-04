package com.foremen.service.bank.connector.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.dao.model.FeedProvider;
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
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.ProviderBank;
import com.foremen.service.bank.connector.RefreshMode;
import com.foremen.service.bank.connector.RefreshResult;
import com.foremen.service.bank.connector.TransactionPage;
import com.foremen.service.bank.connector.TxnStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-07-04 (Requirement 4.4): the dev/test {@code STUB} bank-feed connector.
 *
 * <p>Registered by {@link StubConnectorConfig} only when {@code foremen.bank-feed.stub.enabled = true}.
 * <ul>
 *   <li>Authorization is instant: the authorization URL is the caller-supplied return URL with
 *       {@code stub=1} appended, so the browser lands directly on the return route.</li>
 *   <li>{@link #listBanks} returns two stub banks (business + personal).</li>
 *   <li>Accounts / holder / transactions come from the per-connection {@link StubDataStore};
 *       transactions honour cursors (offset-based, {@link StubProperties#getPageSize()} per page) and
 *       the pending list like a real provider.</li>
 *   <li>{@code failNextFetch} makes the next {@code fetchTransactions} throw that error class once.
 *       The stub has no internal retry loop, so a single TRANSIENT throw equals the whole
 *       (retried) provider call failing for that run.</li>
 * </ul>
 */
public class StubConnector implements BankFeedConnector {

    public static final String BUSINESS_BANK_CODE = "stub_business_pl";
    public static final String PERSONAL_BANK_CODE = "stub_personal_pl";
    static final String STUB_RETURN_PARAM = "stub";

    private static final List<ProviderBank> BANKS = List.of(
            new ProviderBank(BUSINESS_BANK_CODE, "Stub Bank Business", null, null, "active", "web",
                    true, Set.of("business"), Set.of("accounts", "holder_info", "transactions"),
                    true, 90, 24, false, true),
            new ProviderBank(PERSONAL_BANK_CODE, "Stub Bank Personal", null, null, "active", "web",
                    true, Set.of("personal"), Set.of("accounts", "holder_info", "transactions"),
                    true, 90, 24, false, true));

    private final StubDataStore store;
    private final StubProperties properties;
    private final Clock clock;

    public StubConnector(StubDataStore store, StubProperties properties, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public FeedProvider provider() {
        return FeedProvider.STUB;
    }

    /** The bean exists only when the stub is enabled, so it is always available. */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of();
    }

    /** The two stub banks, independent of the requested country. */
    @Override
    public List<ProviderBank> listBanks(String countryCode) {
        return BANKS;
    }

    @Override
    public AuthorizationStart startAuthorization(BankConnectionRef connection, String providerBankCode, URI returnUrl) {
        long id = requireId(connection);
        store.startAuthorization(id, providerBankCode);
        return new AuthorizationStart(instantAuthorizationUrl(returnUrl),
                clock.instant().plus(Duration.ofHours(1)), customerId(id));
    }

    @Override
    public ConnectionState completeAuthorization(BankConnectionRef connection, Map<String, String> returnParams) {
        long id = requireId(connection);
        Instant now = clock.instant();
        store.authorize(id, now, now.plus(Duration.ofDays(properties.getConsentDays())));
        return fetchConnectionState(connection);
    }

    @Override
    public AuthorizationStart startReconnect(BankConnectionRef connection, URI returnUrl) {
        long id = requireId(connection);
        store.startAuthorization(id, null);
        return new AuthorizationStart(instantAuthorizationUrl(returnUrl),
                clock.instant().plus(Duration.ofHours(1)), customerId(id));
    }

    @Override
    public RefreshResult requestRefresh(BankConnectionRef connection, RefreshMode mode) {
        requireReadable(requireId(connection));
        return RefreshResult.started(null, null);
    }

    @Override
    public ConnectionState fetchConnectionState(BankConnectionRef connection) {
        long id = requireId(connection);
        Optional<StubDataStore.Snapshot> snapshot = store.snapshot(id);
        String bankCode = connection.providerCode() != null ? connection.providerCode()
                : snapshot.map(StubDataStore.Snapshot::providerBankCode).orElse(null);
        if (bankCode == null) {
            bankCode = BUSINESS_BANK_CODE;
        }
        ConnectionStatusHint status = snapshot.map(StubConnector::statusHint).orElse(ConnectionStatusHint.PENDING_AUTH);
        Instant consentExpiresAt = snapshot.map(StubDataStore.Snapshot::consentExpiresAt).orElse(null);
        return new ConnectionState(status, connectionExternalId(id), "stub-consent-" + id,
                consentExpiresAt, null, bankCode, bankName(bankCode), null);
    }

    @Override
    public Optional<HolderInfo> fetchHolderInfo(BankConnectionRef connection) {
        return store.snapshot(requireId(connection))
                .map(StubDataStore.Snapshot::holder)
                .filter(holder -> !holder.isEmpty());
    }

    @Override
    public List<NormalizedAccount> fetchAccounts(BankConnectionRef connection) {
        long id = requireId(connection);
        requireReadable(id);
        Instant now = clock.instant();
        return store.accounts(id).stream()
                .map(a -> new NormalizedAccount(a.externalId(), a.name(), a.nature() == null ? "account" : a.nature(),
                        a.iban(), a.accountNumber(), a.currency(), a.balance(), a.availableBalance(),
                        a.holderName(), a.balanceAt() == null ? now : a.balanceAt()))
                .toList();
    }

    @Override
    public TransactionPage fetchTransactions(BankConnectionRef connection, NormalizedAccountRef account, String cursor) {
        long id = requireId(connection);
        requireReadable(id);
        Optional<ConnectorErrorClass> failure = store.consumeFailNextFetch(id);
        if (failure.isPresent()) {
            ConnectorErrorClass errorClass = failure.get();
            throw new ConnectorError(errorClass, "STUB_" + errorClass.name(), null,
                    "Stub fetch failure (" + errorClass + ") for connection " + id);
        }
        int offset = parseCursor(cursor);
        List<StubSeed.Transaction> all = store.transactions(id, account.externalId());
        List<NormalizedTransaction> posted = all.stream()
                .filter(t -> t.status() != TxnStatus.PENDING)
                .map(t -> normalize(t, TxnStatus.POSTED))
                .toList();
        int pageSize = Math.max(1, properties.getPageSize());
        int from = Math.min(offset, posted.size());
        int to = Math.min(from + pageSize, posted.size());
        String nextCursor = to < posted.size() ? String.valueOf(to) : null;
        // Pending rows are a non-paginated snapshot, returned with the first page only.
        List<NormalizedTransaction> pending = offset == 0
                ? all.stream().filter(t -> t.status() == TxnStatus.PENDING).map(t -> normalize(t, TxnStatus.PENDING)).toList()
                : List.of();
        return new TransactionPage(posted.subList(from, to), pending, nextCursor, nextCursor == null, 0);
    }

    @Override
    public void disable(BankConnectionRef connection) {
        store.disable(requireId(connection));
    }

    // ------------------------------------------------------------------ helpers

    /** Appends {@code stub=1} to the return URL, keeping any existing query string. */
    static URI instantAuthorizationUrl(URI returnUrl) {
        Objects.requireNonNull(returnUrl, "returnUrl");
        String url = returnUrl.toString();
        String fragment = "";
        int hash = url.indexOf('#');
        if (hash >= 0) {
            fragment = url.substring(hash);
            url = url.substring(0, hash);
        }
        String separator = url.contains("?") ? (url.endsWith("?") || url.endsWith("&") ? "" : "&") : "?";
        return URI.create(url + separator + STUB_RETURN_PARAM + "=1" + fragment);
    }

    private static ConnectionStatusHint statusHint(StubDataStore.Snapshot snapshot) {
        return switch (snapshot.status()) {
            case DISABLED -> ConnectionStatusHint.DISABLED;
            case REAUTH -> ConnectionStatusHint.REAUTH_REQUIRED;
            case ACTIVE -> snapshot.authorized() ? ConnectionStatusHint.ACTIVE : ConnectionStatusHint.PENDING_AUTH;
        };
    }

    private void requireReadable(long id) {
        StubSeed.Status status = store.snapshot(id).map(StubDataStore.Snapshot::status).orElse(StubSeed.Status.ACTIVE);
        if (status != StubSeed.Status.ACTIVE) {
            throw new ConnectorError(ConnectorErrorClass.REAUTH, "STUB_" + status.name(), null,
                    "Stub connection " + id + " is " + status);
        }
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor.trim());
            if (offset < 0) {
                throw new NumberFormatException(cursor);
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new ConnectorError(ConnectorErrorClass.PROVIDER, "STUB_INVALID_CURSOR", null,
                    "Invalid stub cursor: " + cursor);
        }
    }

    private static NormalizedTransaction normalize(StubSeed.Transaction t, TxnStatus status) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        JsonNode counterpartyRaw = null;
        if (t.counterpartyName() != null || t.counterpartyIban() != null) {
            ObjectNode cp = f.objectNode();
            cp.put("name", t.counterpartyName());
            cp.put("iban", t.counterpartyIban());
            counterpartyRaw = cp;
        }
        ObjectNode payload = f.objectNode();
        payload.put("stub", true);
        payload.put("accountExternalId", t.accountExternalId());
        payload.put("externalId", t.externalId());
        payload.put("status", status.name());
        payload.put("amount", t.amount());
        payload.put("currency", t.currency());
        payload.put("title", t.title());
        return new NormalizedTransaction(t.externalId(), status, t.amount(), t.currency(),
                t.operationDate(), t.bookingDate(), t.title(), t.counterpartyName(), t.counterpartyIban(),
                t.providerCategory(), counterpartyRaw, payload, t.providerDuplicate());
    }

    private static String bankName(String code) {
        return BANKS.stream().filter(b -> b.code().equals(code)).map(ProviderBank::name)
                .findFirst().orElse("Stub Bank");
    }

    private static long requireId(BankConnectionRef connection) {
        if (connection == null || connection.id() == null) {
            throw new IllegalArgumentException("Stub connector requires a persisted connection id");
        }
        return connection.id();
    }

    private static String customerId(long id) {
        return "stub-customer-" + id;
    }

    private static String connectionExternalId(long id) {
        return "stub-conn-" + id;
    }
}
