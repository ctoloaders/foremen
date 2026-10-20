package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.EstimateAmendmentEntity;

/**
 * FOR-06-08 (Requirements 1, 2; design §Backend components) — DAO for
 * {@link EstimateAmendmentEntity}. The generic {@link AdminDao} CRUD + {@code JpaSpecificationExecutor}
 * surface backs the {@code ProjectScopedService} LIST reads (project-filtered via the inherited
 * {@code addRequiredQuery()}) and the by-id reads/mutations (per-id project-access guard); on top of
 * it the service needs three directed finders:
 *
 * <ul>
 *   <li>{@link #findByProjectIdAndStatus(Long, AmendmentStatus)} — the single {@code OPEN} amendment
 *       of a project, if any; backs both the {@code getOpenOrNull} read and the
 *       single-open-amendment create guard (R2.1/R2.3) complementing the partial unique index
 *       {@code ux_amendment_open_per_project};</li>
 *   <li>{@link #findByProjectIdOrderBySeqNoAsc(Long)} — the project's amendments for the list
 *       (R1.1/R1.2), ordered by {@code seqNo};</li>
 *   <li>{@link #findMaxSeqNoByProjectId(Long)} — the current max {@code seqNo} so a create assigns
 *       the next one (R2.2).</li>
 * </ul>
 */
@Repository
public interface EstimateAmendmentDao extends AdminDao<EstimateAmendmentEntity, Long> {

    /**
     * The amendment of {@code projectId} in the given {@code status}, if one exists. Called with
     * {@link AmendmentStatus#OPEN} to back the {@code getOpenOrNull} read and the create-time
     * single-open guard (R2.1/R2.3). At most one {@code OPEN} amendment can exist per project (the
     * partial unique index), so the result is an {@link Optional}.
     *
     * @param projectId the owning project id
     * @param status    the amendment status to match (the service passes {@code OPEN})
     * @return the matching amendment, or empty
     */
    Optional<EstimateAmendmentEntity> findByProjectIdAndStatus(Long projectId, AmendmentStatus status);

    /**
     * The project's amendments ordered by {@code seqNo} ascending, for the amendments list
     * (R1.1/R1.2).
     *
     * @param projectId the owning project id
     * @return the project's amendments, oldest {@code seqNo} first
     */
    List<EstimateAmendmentEntity> findByProjectIdOrderBySeqNoAsc(Long projectId);

    /**
     * The current maximum {@code seqNo} across the project's amendments, or {@code null} when the
     * project has none yet, so a create assigns {@code max + 1} (or {@code 1} for the first) as the
     * next {@code seqNo} (R2.2).
     *
     * @param projectId the owning project id
     * @return the max {@code seqNo}, or {@code null} when the project has no amendments
     */
    @Query("SELECT MAX(a.seqNo) FROM EstimateAmendmentEntity a WHERE a.project.id = :projectId")
    Integer findMaxSeqNoByProjectId(@Param("projectId") Long projectId);

    /**
     * FOR-06-09 (Requirements 2.2, 2.3, 3): the amendment whose linked signing document is
     * {@code documentId}, if any — the reverse lookup the after-commit reaction needs to map a
     * document's terminal transition back onto its amendment. At most one amendment ever links a given
     * document (the link is set once on send-for-signing and cleared on reopen), so the result is an
     * {@link Optional}.
     *
     * @param documentId the linked {@code SignableDocument} id
     * @return the amendment linked to that document, or empty
     */
    Optional<EstimateAmendmentEntity> findBySignableDocumentId(Long documentId);
}
