package com.foremen.service.bank.sync;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.connector.AuthorizationStart;
import com.foremen.service.bank.connector.BankConnectionRef;
import com.foremen.service.bank.connector.BankFeedConnector;
import com.foremen.service.bank.connector.ConnectionState;
import com.foremen.service.bank.connector.ConnectionStatusHint;
import com.foremen.service.bank.connector.ConnectorError;
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

/**
 * Scripted in-memory connector for the sync unit tests. Transactions are served as a list of pages per
 * account: the first fetch of a run (any start cursor) returns page 0, page {@code i} continues with
 * the cursor {@code "page:i"}. Every call is recorded.
 */
class FakeBankFeedConnector implements BankFeedConnector {

    final FeedProvider provider;
    ConnectionState state = new ConnectionState(ConnectionStatusHint.ACTIVE, "ext-1", "consent-1",
            Instant.parse("2030-01-01T00:00:00Z"), null, "fake_bank", "Fake Bank", null);
    ConnectorError stateError;
    HolderInfo holder = new HolderInfo(List.of("Foremen sp. z o.o."), List.of("1234567890"));
    ConnectorError holderError;
    List<NormalizedAccount> accounts = new ArrayList<>();
    ConnectorError accountsError;
    final Map<String, List<TransactionPage>> pages = new HashMap<>();
    final Map<String, ConnectorError> accountErrors = new HashMap<>();

    final List<String> fetchCalls = new ArrayList<>();
    int stateCalls;
    int refreshCalls;

    FakeBankFeedConnector(FeedProvider provider) {
        this.provider = provider;
    }

    static NormalizedAccount account(String externalId, BigDecimal balance, Instant balanceAt) {
        return new NormalizedAccount(externalId, "Account " + externalId, "account", null, null, "PLN",
                balance, balance, "Foremen sp. z o.o.", balanceAt);
    }

    static NormalizedTransaction posted(String externalId, String amount) {
        return new NormalizedTransaction(externalId, TxnStatus.POSTED, new BigDecimal(amount), "PLN",
                LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 10), "t " + externalId, null, null, null,
                null, null, false);
    }

    static TransactionPage page(List<NormalizedTransaction> posted, String next) {
        return new TransactionPage(posted, List.of(), next, next == null, 0);
    }

    @Override
    public FeedProvider provider() {
        return provider;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of();
    }

    @Override
    public List<ProviderBank> listBanks(String countryCode) {
        return List.of();
    }

    @Override
    public AuthorizationStart startAuthorization(BankConnectionRef connection, String providerBankCode, URI returnUrl) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConnectionState completeAuthorization(BankConnectionRef connection, Map<String, String> returnParams) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AuthorizationStart startReconnect(BankConnectionRef connection, URI returnUrl) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RefreshResult requestRefresh(BankConnectionRef connection, RefreshMode mode) {
        refreshCalls++;
        return RefreshResult.started(null, null);
    }

    @Override
    public ConnectionState fetchConnectionState(BankConnectionRef connection) {
        stateCalls++;
        if (stateError != null) {
            throw stateError;
        }
        return state;
    }

    @Override
    public Optional<HolderInfo> fetchHolderInfo(BankConnectionRef connection) {
        if (holderError != null) {
            throw holderError;
        }
        return Optional.ofNullable(holder);
    }

    @Override
    public List<NormalizedAccount> fetchAccounts(BankConnectionRef connection) {
        if (accountsError != null) {
            throw accountsError;
        }
        return accounts;
    }

    @Override
    public TransactionPage fetchTransactions(BankConnectionRef connection, NormalizedAccountRef account, String cursor) {
        fetchCalls.add(account.externalId() + "@" + cursor);
        ConnectorError error = accountErrors.get(account.externalId());
        if (error != null) {
            throw error;
        }
        List<TransactionPage> list = pages.getOrDefault(account.externalId(), List.of(page(List.of(), null)));
        int index = cursor != null && cursor.startsWith("page:") ? Integer.parseInt(cursor.substring(5)) : 0;
        return list.get(index);
    }

    @Override
    public void disable(BankConnectionRef connection) {
        throw new UnsupportedOperationException();
    }
}
