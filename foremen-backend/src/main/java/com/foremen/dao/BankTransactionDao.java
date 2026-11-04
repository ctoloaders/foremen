package com.foremen.dao;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.SystemSource;

/**
 * FOR-07-04 (Requirements 6.1, 6.2, 17.3, 27.2) — DAO for {@link BankTransactionEntity} (ledger lines,
 * bank and cash). The ledger list itself goes through the {@code JpaSpecificationExecutor} surface.
 */
@Repository
public interface BankTransactionDao extends AdminDao<BankTransactionEntity, Long> {

    /** The line for a provider transaction id within an account (ingest idempotency key). */
    Optional<BankTransactionEntity> findByAccountIdAndExternalId(Long accountId, String externalId);

    /** Lines of an account for a set of provider ids (batched ingest lookup). */
    List<BankTransactionEntity> findByAccountIdAndExternalIdIn(Long accountId, Collection<String> externalIds);

    /** Lines of an account in a status (e.g. PENDING lines to replace). */
    List<BankTransactionEntity> findByAccountIdAndStatus(Long accountId, BankTxnStatus status);

    /** Lines with a counterparty key (rule application to history). */
    List<BankTransactionEntity> findByCounterpartyKey(String counterpartyKey);

    /** Lines with a counterparty key in the given link statuses. */
    List<BankTransactionEntity> findByCounterpartyKeyAndLinkStatusIn(String counterpartyKey,
                                                                     Collection<LinkStatus> linkStatuses);

    /** The SYSTEM cash line produced by a payout / document (at most one, partial UNIQUE). */
    Optional<BankTransactionEntity> findBySystemSourceAndSystemSourceId(SystemSource systemSource,
                                                                        Long systemSourceId);

    /**
     * Lines of an account in a status booked within {@code [from, to]} — the POSTED amounts of the
     * balance continuity check (Requirement 7.1; the checker applies the exact window bounds).
     */
    List<BankTransactionEntity> findByAccountIdAndStatusAndBookingDateBetween(Long accountId, BankTxnStatus status,
                                                                              LocalDate from, LocalDate to);

    /** Lines first ingested by a run in the given link statuses ({@code toReview}). */
    long countByFirstSyncRunIdAndLinkStatusIn(Long runId, Collection<LinkStatus> linkStatuses);

    /**
     * FOR-07-04 (Requirement 28.2, design "Reverse document matching") — POSTED expense lines (bank and
     * cash, {@code amount < 0}) in the given link statuses booked within {@code [from, to]}, account
     * fetch-joined, by id.
     */
    @Query("SELECT t FROM BankTransactionEntity t JOIN FETCH t.account "
            + "WHERE t.status = com.foremen.dao.model.BankTxnStatus.POSTED AND t.amount < 0 "
            + "AND t.linkStatus IN :linkStatuses AND t.bookingDate BETWEEN :from AND :to "
            + "ORDER BY t.id")
    List<BankTransactionEntity> findExpenseLinesForDocumentMatching(
            @Param("linkStatuses") Collection<LinkStatus> linkStatuses,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
