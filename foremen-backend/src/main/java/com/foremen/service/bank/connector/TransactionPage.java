package com.foremen.service.bank.connector;

import java.util.List;

/**
 * FOR-07-04 (Requirement 4.1): one page of transactions returned by
 * {@link BankFeedConnector#fetchTransactions}.
 *
 * @param posted           posted transactions of the page
 * @param pending          pending transactions (the full current pending set when {@code complete})
 * @param nextCursor       cursor for the next call; {@code null} when there is nothing more
 * @param complete         {@code true} when this is the last page
 * @param skippedMalformed provider rows skipped because they could not be normalized
 */
public record TransactionPage(
        List<NormalizedTransaction> posted,
        List<NormalizedTransaction> pending,
        String nextCursor,
        boolean complete,
        int skippedMalformed) {

    public TransactionPage {
        posted = posted == null ? List.of() : List.copyOf(posted);
        pending = pending == null ? List.of() : List.copyOf(pending);
    }
}
