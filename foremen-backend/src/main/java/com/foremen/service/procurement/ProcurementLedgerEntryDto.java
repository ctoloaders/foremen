package com.foremen.service.procurement;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-07-04 (Requirements 16.4, 18.3, 28.4) — one ledger line linked ({@code PROCUREMENT}) to a
 * procurement document, exposed on the document read model so the procurement list / detail can
 * render a link to {@code /company/bank-transactions?transactionId={transactionId}}.
 *
 * @param transactionId the {@code bank_transactions.id} of the linked ledger line
 * @param channel       the ledger line channel: {@code BANK} or {@code CASH}
 * @param bookingDate   the ledger line booking date
 * @param accountName   the display name of the bank / cash account holding the line
 * @param amount        the linked amount ({@code bank_transaction_links.amount}, positive)
 */
public record ProcurementLedgerEntryDto(
        Long transactionId,
        String channel,
        LocalDate bookingDate,
        String accountName,
        BigDecimal amount) {
}
