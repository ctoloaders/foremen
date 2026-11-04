package com.foremen.service.procurement;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;

/**
 * FOR-07-04 (Requirements 16.4, 18.3, 28.4) — the {@code bank_transaction_links}-backed
 * {@link ProcurementLedgerEntriesProvider}: one {@link ProcurementLedgerEntryDto} per
 * {@code PROCUREMENT} link of the document (ledger line fetched), ordered by booking date.
 */
@Component
public class BankLinkProcurementLedgerEntriesProvider implements ProcurementLedgerEntriesProvider {

    private final BankTransactionLinkDao linkDao;

    public BankLinkProcurementLedgerEntriesProvider(BankTransactionLinkDao linkDao) {
        this.linkDao = linkDao;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcurementLedgerEntryDto> forDocument(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        return linkDao.findByProcurementDocumentId(documentId).stream()
                .map(BankLinkProcurementLedgerEntriesProvider::toEntry)
                .toList();
    }

    private static ProcurementLedgerEntryDto toEntry(BankTransactionLinkEntity link) {
        BankTransactionEntity txn = link.getTransaction();
        return new ProcurementLedgerEntryDto(
                txn.getId(),
                txn.getChannel() == null ? null : txn.getChannel().name(),
                txn.getBookingDate(),
                txn.getAccount() == null ? null : txn.getAccount().getName(),
                link.getAmount());
    }
}
