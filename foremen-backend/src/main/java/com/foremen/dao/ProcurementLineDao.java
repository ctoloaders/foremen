package com.foremen.dao;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ProcurementLineEntity;

/**
 * FOR-06-04 (design §5.1) — DAO for {@link ProcurementLineEntity}, the reconciliation unit of the
 * project-wide purchases list.
 *
 * <p>FOR-06-03 persists lines only through the {@code ProcurementDocument} aggregate
 * ({@code @OneToMany cascade = ALL, orphanRemoval = true}) and needs no line DAO. FOR-06-04 adds this
 * DAO because its {@code PurchaseLineReconciliationService} is project-scoped <strong>over the line</strong>
 * (not the document): re-link / clear-match / classify / clear-classification mutate a single line by
 * id, and the reconciled list reads lines across all of a project's documents. The generic
 * {@link AdminDao} CRUD + {@code JpaSpecificationExecutor} surface is sufficient — the service is a
 * {@code ProjectScopedService} resolving {@code "document.project.id"}, so by-id reads/mutations go
 * through the inherited {@code findById}/{@code save} with the per-id project-access guard and LIST
 * reads flow through the inherited specification ({@code addRequiredQuery()} project filter).
 */
@Repository
public interface ProcurementLineDao extends AdminDao<ProcurementLineEntity, Long> {

    /**
     * FOR-06-06 (R3.1, R3.2, design §B2) — every {@link ProcurementLineEntity} across a project's
     * procurement documents, the material + indirect-fact side of the margin matrix, with each line's
     * {@link ProcurementLineEntity#getUnplannedCategory() unplanned category} fetch-joined so the
     * margin-matrix service can bucket the indirect cost by category (+ the uncategorized bucket)
     * without an N+1 over the LAZY association. The margin-matrix service derives each line's
     * {@code linked} flag ({@code matchedMaterialId != null} with {@code matchStatus ∈ {AUTO,
     * MANUAL_MATCHED}}) and sums {@code lineTotal} into the linked-material or indirect fold; the raw
     * lines are returned verbatim here (no auth scoping — the service has already resolved + scoped the
     * project). Ordered by {@code (document, lineNo)} for a stable read.
     *
     * @param projectId the owning project id
     * @return the project's procurement lines with the unplanned category fetched, empty when none
     */
    @Query("SELECT l FROM ProcurementLineEntity l "
            + "LEFT JOIN FETCH l.unplannedCategory "
            + "WHERE l.document.project.id = :projectId "
            + "ORDER BY l.document.id, l.lineNo")
    List<ProcurementLineEntity> findByProjectIdWithUnplannedCategory(@Param("projectId") Long projectId);
}
