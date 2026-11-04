package com.foremen.dao;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankTransactionLinkEntity;

/**
 * FOR-07-04 (Requirements 13.1, 16.3, 18.3) — DAO for {@link BankTransactionLinkEntity}: links of a
 * line, links of a target, and Σ link amount per payout / document (feeds
 * {@code DocumentPaymentStatusDeriver} and the payout read model).
 */
@Repository
public interface BankTransactionLinkDao extends AdminDao<BankTransactionLinkEntity, Long> {

    /** Links of a ledger line, oldest first. */
    List<BankTransactionLinkEntity> findByTransactionIdOrderByIdAsc(Long transactionId);

    /** Links of many ledger lines (list view batch). */
    List<BankTransactionLinkEntity> findByTransactionIdIn(Collection<Long> transactionIds);

    /** Links to a payout, with the ledger line fetched, by booking date. */
    @Query("SELECT l FROM BankTransactionLinkEntity l JOIN FETCH l.transaction t "
            + "WHERE l.workerPayout.id = :payoutId ORDER BY t.bookingDate, t.id")
    List<BankTransactionLinkEntity> findByWorkerPayoutId(@Param("payoutId") Long payoutId);

    /** Links to a procurement document, with the ledger line fetched, by booking date. */
    @Query("SELECT l FROM BankTransactionLinkEntity l JOIN FETCH l.transaction t "
            + "WHERE l.procurementDocument.id = :documentId ORDER BY t.bookingDate, t.id")
    List<BankTransactionLinkEntity> findByProcurementDocumentId(@Param("documentId") Long documentId);

    /** Σ link amount to a payout; 0 when none. */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM BankTransactionLinkEntity l WHERE l.workerPayout.id = :payoutId")
    BigDecimal sumAmountByWorkerPayoutId(@Param("payoutId") Long payoutId);

    /** Σ link amount to a procurement document; 0 when none. */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM BankTransactionLinkEntity l "
            + "WHERE l.procurementDocument.id = :documentId")
    BigDecimal sumAmountByProcurementDocumentId(@Param("documentId") Long documentId);

    /** Σ link amount per payout for many payouts (payouts without links are absent). */
    @Query("SELECT new com.foremen.dao.BankLinkedAmount(l.workerPayout.id, SUM(l.amount)) "
            + "FROM BankTransactionLinkEntity l WHERE l.workerPayout.id IN :payoutIds GROUP BY l.workerPayout.id")
    List<BankLinkedAmount> sumAmountByWorkerPayoutIds(@Param("payoutIds") Collection<Long> payoutIds);

    /** Σ link amount per document for many documents (documents without links are absent). */
    @Query("SELECT new com.foremen.dao.BankLinkedAmount(l.procurementDocument.id, SUM(l.amount)) "
            + "FROM BankTransactionLinkEntity l WHERE l.procurementDocument.id IN :documentIds "
            + "GROUP BY l.procurementDocument.id")
    List<BankLinkedAmount> sumAmountByProcurementDocumentIds(@Param("documentIds") Collection<Long> documentIds);
}
