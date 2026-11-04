package com.foremen.service.bank.connector.stub;

import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.TxnStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * FOR-07-04 (Requirement 4.4): the stub seed body accepted by
 * {@code POST /api/bank/stub/connections/{id}/data} and applied by {@link StubDataStore#seed}.
 *
 * <p>{@code mode = REPLACE} (default) replaces all stub data of the connection; {@code APPEND}
 * upserts accounts / transactions by {@code externalId} and only overrides the fields that are set.
 *
 * @param mode          REPLACE (default) or APPEND
 * @param state         connection status + consent expiry; null keeps the current state
 * @param holder        holder names / tax ids; drives {@code Holder_Match}
 * @param failNextFetch makes the next {@code fetchTransactions} throw this error class once
 * @param accounts      accounts of the connection
 * @param transactions  posted / pending transactions, each bound to an account by {@code accountExternalId}
 */
public record StubSeed(
        Mode mode,
        State state,
        HolderInfo holder,
        ConnectorErrorClass failNextFetch,
        List<Account> accounts,
        List<Transaction> transactions) {

    public StubSeed {
        mode = mode == null ? Mode.REPLACE : mode;
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
    }

    public enum Mode { REPLACE, APPEND }

    /** Stub connection status accepted by the seed. */
    public enum Status { ACTIVE, REAUTH, DISABLED }

    public record State(Status status, Instant consentExpiresAt) {
    }

    public record Account(
            String externalId,
            String name,
            String nature,
            String iban,
            String accountNumber,
            String currency,
            BigDecimal balance,
            BigDecimal availableBalance,
            String holderName,
            Instant balanceAt) {
    }

    public record Transaction(
            String accountExternalId,
            String externalId,
            TxnStatus status,
            BigDecimal amount,
            String currency,
            LocalDate operationDate,
            LocalDate bookingDate,
            String title,
            String counterpartyName,
            String counterpartyIban,
            String providerCategory,
            Boolean providerDuplicate) {
        /**
         * Nullable on the wire so a seed may omit the flag (Jackson fails a missing primitive under
         * {@code FAIL_ON_NULL_FOR_PRIMITIVES}); normalized to {@code false}.
         */
        public Transaction {
            providerDuplicate = Boolean.TRUE.equals(providerDuplicate);
        }
    }
}
