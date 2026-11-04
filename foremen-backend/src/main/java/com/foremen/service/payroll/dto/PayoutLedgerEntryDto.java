package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-07-04 (Requirement 16.3; design §Link effects — payout read model) — one ledger line linked to a
 * payout, exposed on {@link WorkerPayoutDto#ledgerEntries()} so the payroll card can render a link to
 * {@code /company/bank-transactions?transactionId={transactionId}}.
 *
 * @param transactionId the {@code bank_transactions.id} of the linked ledger line
 * @param channel       the ledger line channel: {@code BANK} or {@code CASH}
 * @param bookingDate   the ledger line booking date
 * @param accountName   the display name of the bank / cash account holding the line
 * @param amount        the linked amount ({@code bank_transaction_links.amount}, positive)
 */
public record PayoutLedgerEntryDto(
        Long transactionId,
        String channel,
        LocalDate bookingDate,
        String accountName,
        BigDecimal amount) {
}
