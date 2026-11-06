package com.foremen.dao;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;

import jakarta.persistence.LockModeType;

/**
 * FOR-05-02 (Requirements 2.3, 2.4, 19.1; design B2a) — DAO for {@link RoomsImportJobEntity}.
 * There is at most one job row per project ({@code UNIQUE (project_id)}).
 */
@Repository
public interface RoomsImportJobDao extends AdminDao<RoomsImportJobEntity, Long> {

    /** The terminal statuses, for the {@code NOT IN} / {@code IN} guards below. */
    List<RoomsImportStatus> TERMINAL = List.of(RoomsImportStatus.COMPLETED, RoomsImportStatus.FAILED);

    /**
     * @param projectId the project id
     * @return the project's single job row, if any
     */
    @Query("SELECT j FROM RoomsImportJobEntity j WHERE j.project.id = :projectId")
    Optional<RoomsImportJobEntity> findByProjectId(@Param("projectId") Long projectId);

    /**
     * Same as {@link #findByProjectId} but takes a pessimistic write lock ({@code SELECT … FOR
     * UPDATE}) so concurrent starts for one project are serialized.
     *
     * @param projectId the project id
     * @return the locked job row, if any
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM RoomsImportJobEntity j WHERE j.project.id = :projectId")
    Optional<RoomsImportJobEntity> findByProjectIdForUpdate(@Param("projectId") Long projectId);

    /**
     * Moves an active job to {@code status}. Status-conditional: a terminal job, or a job whose row is
     * gone (cancelled / replaced), is not touched and {@code 0} is returned.
     *
     * @return the number of updated rows (0 or 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoomsImportJobEntity j SET j.status = :status, j.updatedDate = :now "
            + "WHERE j.id = :id AND j.status NOT IN :terminal")
    int updateStatusIfActive(@Param("id") Long id, @Param("status") RoomsImportStatus status,
                             @Param("now") LocalDateTime now,
                             @Param("terminal") List<RoomsImportStatus> terminal);

    /** Bumps {@code updatedDate} of an active job (heartbeat); {@code 0} when it is gone or terminal. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoomsImportJobEntity j SET j.updatedDate = :now "
            + "WHERE j.id = :id AND j.status NOT IN :terminal")
    int touchIfActive(@Param("id") Long id, @Param("now") LocalDateTime now,
                      @Param("terminal") List<RoomsImportStatus> terminal);

    /**
     * Status-conditional terminal transition ({@code COMPLETED} / {@code FAILED}): applies only while the
     * job is still active, so exactly one of two racing callers (worker vs sweep) wins.
     *
     * @return the number of updated rows (0 or 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoomsImportJobEntity j SET j.status = :status, j.failureReason = :reason, "
            + "j.completedAt = :now, j.updatedDate = :now, j.expiresAt = :expiresAt, "
            + "j.roomsDetected = :rooms, j.warningsCount = :warnings, j.modelId = :modelId "
            + "WHERE j.id = :id AND j.status NOT IN :terminal")
    int terminateIfActive(@Param("id") Long id, @Param("status") RoomsImportStatus status,
                          @Param("reason") String reason, @Param("now") LocalDateTime now,
                          @Param("expiresAt") LocalDateTime expiresAt, @Param("rooms") Integer rooms,
                          @Param("warnings") Integer warnings, @Param("modelId") String modelId,
                          @Param("terminal") List<RoomsImportStatus> terminal);

    /** Conditional delete used by cancel: removes the row only while the job is active. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RoomsImportJobEntity j WHERE j.id = :id AND j.status NOT IN :terminal")
    int deleteIfActive(@Param("id") Long id, @Param("terminal") List<RoomsImportStatus> terminal);

    /** Unconditional delete by id (discard / TTL cleanup); returns the number of deleted rows. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RoomsImportJobEntity j WHERE j.id = :id")
    int deleteByJobId(@Param("id") Long id);

    /** Ids of active jobs whose last update is older than {@code cutoff} (stuck jobs). */
    @Query("SELECT j.id FROM RoomsImportJobEntity j WHERE j.status NOT IN :terminal AND j.updatedDate < :cutoff")
    List<Long> findStuckIds(@Param("cutoff") LocalDateTime cutoff,
                            @Param("terminal") List<RoomsImportStatus> terminal);

    /** Ids of terminal jobs whose retention has elapsed. */
    @Query("SELECT j.id FROM RoomsImportJobEntity j WHERE j.status IN :terminal AND j.expiresAt < :now")
    List<Long> findExpiredIds(@Param("now") LocalDateTime now,
                              @Param("terminal") List<RoomsImportStatus> terminal);
}
