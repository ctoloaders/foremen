package com.foremen.dao;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.SyncRunStatus;

/**
 * FOR-07-04 (Requirements 5.2, 6.1) — DAO for {@link BankSyncRunEntity}.
 */
@Repository
public interface BankSyncRunDao extends AdminDao<BankSyncRunEntity, Long> {

    /**
     * The active ({@code QUEUED} / {@code RUNNING}) run of a connection — at most one by the partial
     * UNIQUE index {@code ux_bank_sync_runs_one_active}.
     */
    @Query("SELECT r FROM BankSyncRunEntity r WHERE r.connection.id = :connectionId "
            + "AND r.status IN (com.foremen.dao.model.SyncRunStatus.QUEUED, "
            + "com.foremen.dao.model.SyncRunStatus.RUNNING)")
    Optional<BankSyncRunEntity> findActiveByConnectionId(@Param("connectionId") Long connectionId);

    /** Runs in the given statuses, oldest first (stale-run recovery, start-up resubmission). */
    List<BankSyncRunEntity> findByStatusInOrderByIdAsc(Collection<SyncRunStatus> statuses);

    /** Runs of a connection, newest first (history, paged). */
    List<BankSyncRunEntity> findByConnectionIdOrderByIdDesc(Long connectionId, Pageable pageable);
}
