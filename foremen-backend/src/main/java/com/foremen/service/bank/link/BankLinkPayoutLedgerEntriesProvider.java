package com.foremen.service.bank.link;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.service.payroll.PayoutLedgerEntriesProvider;
import com.foremen.service.payroll.dto.PayoutLedgerEntryDto;

/**
 * FOR-07-04 (Requirement 16.3) — the {@code bank_transaction_links}-backed
 * {@link PayoutLedgerEntriesProvider}: one {@link PayoutLedgerEntryDto} per {@code WORKER_PAYOUT} link of
 * the payout (ledger line fetched), ordered by booking date. Feeds {@code WorkerPayoutDto.ledgerEntries}.
 */
@Component
public class BankLinkPayoutLedgerEntriesProvider implements PayoutLedgerEntriesProvider {

    private final BankTransactionLinkDao linkDao;

    public BankLinkPayoutLedgerEntriesProvider(BankTransactionLinkDao linkDao) {
        this.linkDao = linkDao;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutLedgerEntryDto> forPayout(Long payoutId) {
        if (payoutId == null) {
            return List.of();
        }
        return linkDao.findByWorkerPayoutId(payoutId).stream()
                .map(BankLinkPayoutLedgerEntriesProvider::toEntry)
                .toList();
    }

    private static PayoutLedgerEntryDto toEntry(BankTransactionLinkEntity link) {
        BankTransactionEntity txn = link.getTransaction();
        return new PayoutLedgerEntryDto(
                txn.getId(),
                txn.getChannel() == null ? null : txn.getChannel().name(),
                txn.getBookingDate(),
                txn.getAccount() == null ? null : txn.getAccount().getName(),
                link.getAmount());
    }
}
