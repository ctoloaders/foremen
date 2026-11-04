package com.foremen.service.payroll;

import java.util.List;

import com.foremen.service.payroll.dto.PayoutLedgerEntryDto;

/**
 * FOR-07-04 (Requirement 16.3; design §Link effects — payout read model) — the seam through which the
 * payroll read model obtains the ledger lines linked to a payout, without the payroll package depending
 * on the bank-ledger entities / DAOs.
 *
 * <p>The bank ledger (FOR-07-04 tasks 3.1 / 6.7) contributes a Spring bean implementing this interface,
 * backed by the {@code bank_transaction_links} DAO ({@code worker_payout_id}). Until such a bean exists,
 * {@code WorkerPayoutDtoMapper} falls back to {@link #NONE}, so {@code ledgerEntries} is always an empty
 * list rather than {@code null}.
 */
@FunctionalInterface
public interface PayoutLedgerEntriesProvider {

    /** The fallback used while the ledger is absent: every payout has no linked ledger line. */
    PayoutLedgerEntriesProvider NONE = payoutId -> List.of();

    /**
     * The ledger lines linked to the payout, ordered by booking date. Never {@code null}.
     *
     * @param payoutId the payout id ({@code null} → empty)
     * @return the linked ledger lines
     */
    List<PayoutLedgerEntryDto> forPayout(Long payoutId);
}
