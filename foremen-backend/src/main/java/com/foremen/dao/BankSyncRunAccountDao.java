package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankSyncRunAccountEntity;

/**
 * FOR-07-04 (Requirement 6.2) — DAO for {@link BankSyncRunAccountEntity}.
 */
@Repository
public interface BankSyncRunAccountDao extends AdminDao<BankSyncRunAccountEntity, Long> {

    /** Per-account rows of a run. */
    List<BankSyncRunAccountEntity> findByRunIdOrderByIdAsc(Long runId);

    /** The row of one account in a run (UNIQUE {@code (run_id, account_id)}). */
    Optional<BankSyncRunAccountEntity> findByRunIdAndAccountId(Long runId, Long accountId);
}
