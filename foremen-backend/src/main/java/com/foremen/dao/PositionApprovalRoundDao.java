package com.foremen.dao;

import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** FOR-11-02: approval rounds. */
@Repository
public interface PositionApprovalRoundDao extends AdminDao<PositionApprovalRoundEntity, Long> {

    /** Round row lock for decisions (R7.1, concurrency). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PositionApprovalRoundEntity r WHERE r.id = :id")
    Optional<PositionApprovalRoundEntity> findByIdForUpdate(@Param("id") Long id);

    /** The open non-suggestion round of a position, if any. */
    @Query("SELECT r FROM PositionApprovalRoundEntity r WHERE r.position.id = :positionId "
            + "AND r.status = :status "
            + "AND r.kind <> com.foremen.service.procurement.approval.ApprovalRoundKind.CLIENT_SUGGESTION")
    Optional<PositionApprovalRoundEntity> findOpenByPositionId(@Param("positionId") Long positionId,
                                                               @Param("status") ApprovalRoundStatus status);

    /** Rounds of the given positions, ordered by position and round number. */
    @Query("SELECT r FROM PositionApprovalRoundEntity r WHERE r.position.id IN :positionIds "
            + "ORDER BY r.position.id, r.roundNo")
    List<PositionApprovalRoundEntity> findByPositionIds(@Param("positionIds") Collection<Long> positionIds);

    @Query("SELECT COALESCE(MAX(r.roundNo), 0) FROM PositionApprovalRoundEntity r WHERE r.position.id = :positionId")
    int maxRoundNo(@Param("positionId") Long positionId);
}
