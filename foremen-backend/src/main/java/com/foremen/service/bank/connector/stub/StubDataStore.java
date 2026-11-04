package com.foremen.service.bank.connector.stub;

import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.HolderInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FOR-07-04 (Requirement 4.4): in-memory, per-connection data of the {@code STUB} connector.
 *
 * <p>Holds the authorization state, holder info, accounts and transactions of each stub connection,
 * keyed by the ledger connection id. Data is seeded by tests (via the stub-only controller) and read
 * by {@link StubConnector}. All public methods are synchronized per store: the stub is a dev/test
 * component, so simplicity beats throughput.
 */
public class StubDataStore {

    private final Map<Long, ConnectionData> connections = new ConcurrentHashMap<>();

    /** Mutable per-connection state. Accounts / transactions keep insertion order (stable cursors). */
    static final class ConnectionData {
        String providerBankCode;
        boolean authorized;
        StubSeed.Status status = StubSeed.Status.ACTIVE;
        Instant consentExpiresAt;
        HolderInfo holder;
        ConnectorErrorClass failNextFetch;
        final Map<String, StubSeed.Account> accounts = new LinkedHashMap<>();
        /** key = accountExternalId + '\u0000' + externalId. */
        final Map<String, StubSeed.Transaction> transactions = new LinkedHashMap<>();
    }

    /** Applies a seed body to a connection (REPLACE clears all data first; APPEND upserts). */
    public synchronized void seed(long connectionId, StubSeed seed) {
        ConnectionData data = connections.computeIfAbsent(connectionId, id -> new ConnectionData());
        boolean replace = seed.mode() == StubSeed.Mode.REPLACE;
        if (replace) {
            data.accounts.clear();
            data.transactions.clear();
            data.holder = null;
            data.failNextFetch = null;
        }
        if (seed.state() != null) {
            if (seed.state().status() != null) {
                data.status = seed.state().status();
            }
            if (seed.state().consentExpiresAt() != null) {
                data.consentExpiresAt = seed.state().consentExpiresAt();
            }
        }
        if (seed.holder() != null) {
            data.holder = seed.holder();
        }
        if (seed.failNextFetch() != null) {
            data.failNextFetch = seed.failNextFetch();
        }
        for (StubSeed.Account account : seed.accounts()) {
            requireText(account.externalId(), "account.externalId");
            data.accounts.put(account.externalId(), account);
        }
        for (StubSeed.Transaction txn : seed.transactions()) {
            requireText(txn.accountExternalId(), "transaction.accountExternalId");
            requireText(txn.externalId(), "transaction.externalId");
            // Upsert by id keeps the original position, so a PENDING row re-seeded as POSTED
            // replaces itself like a real provider would.
            data.transactions.put(txnKey(txn.accountExternalId(), txn.externalId()), txn);
        }
    }

    /** Records the start of an (instant) authorization for the connection. */
    public synchronized void startAuthorization(long connectionId, String providerBankCode) {
        ConnectionData data = connections.computeIfAbsent(connectionId, id -> new ConnectionData());
        if (providerBankCode != null) {
            data.providerBankCode = providerBankCode;
        }
    }

    /** Marks the connection authorized and ACTIVE; sets the consent expiry when none is seeded. */
    public synchronized void authorize(long connectionId, Instant now, Instant defaultConsentExpiresAt) {
        ConnectionData data = connections.computeIfAbsent(connectionId, id -> new ConnectionData());
        data.authorized = true;
        data.status = StubSeed.Status.ACTIVE;
        if (data.consentExpiresAt == null || !data.consentExpiresAt.isAfter(now)) {
            data.consentExpiresAt = defaultConsentExpiresAt;
        }
    }

    /** Sets the connection DISABLED (data kept so history reads stay deterministic). */
    public synchronized void disable(long connectionId) {
        ConnectionData data = connections.get(connectionId);
        if (data != null) {
            data.status = StubSeed.Status.DISABLED;
        }
    }

    /** Returns and clears the one-shot fetch failure, if any. */
    public synchronized Optional<ConnectorErrorClass> consumeFailNextFetch(long connectionId) {
        ConnectionData data = connections.get(connectionId);
        if (data == null || data.failNextFetch == null) {
            return Optional.empty();
        }
        ConnectorErrorClass errorClass = data.failNextFetch;
        data.failNextFetch = null;
        return Optional.of(errorClass);
    }

    /** Snapshot of the connection's state (copy; never the live object). */
    public synchronized Optional<Snapshot> snapshot(long connectionId) {
        ConnectionData data = connections.get(connectionId);
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(data.providerBankCode, data.authorized, data.status,
                data.consentExpiresAt, data.holder));
    }

    public synchronized List<StubSeed.Account> accounts(long connectionId) {
        ConnectionData data = connections.get(connectionId);
        return data == null ? List.of() : List.copyOf(data.accounts.values());
    }

    /** Transactions of one account in seed order. */
    public synchronized List<StubSeed.Transaction> transactions(long connectionId, String accountExternalId) {
        ConnectionData data = connections.get(connectionId);
        if (data == null) {
            return List.of();
        }
        List<StubSeed.Transaction> result = new ArrayList<>();
        for (StubSeed.Transaction txn : data.transactions.values()) {
            if (txn.accountExternalId().equals(accountExternalId)) {
                result.add(txn);
            }
        }
        return result;
    }

    /** Drops all data of a connection. */
    public synchronized void clear(long connectionId) {
        connections.remove(connectionId);
    }

    /** Read-only view of a connection's state. */
    public record Snapshot(String providerBankCode, boolean authorized, StubSeed.Status status,
                           Instant consentExpiresAt, HolderInfo holder) {
    }

    private static String txnKey(String accountExternalId, String externalId) {
        return accountExternalId + '\u0000' + externalId;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
