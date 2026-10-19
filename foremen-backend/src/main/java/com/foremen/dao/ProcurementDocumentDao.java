package com.foremen.dao;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ProcurementDocumentEntity;

/**
 * FOR-06-03 (design §5.1) — DAO for {@link ProcurementDocumentEntity}. The generic {@link AdminDao}
 * CRUD + {@code JpaSpecificationExecutor} surface is sufficient for {@code ProcurementDocumentService}:
 * it is a {@code ProjectScopedService}, so its LIST reads flow through the inherited specification
 * ({@code addRequiredQuery()} project filter on {@code "project.id"}) and its by-id reads/mutations
 * go through the inherited {@code findById}/{@code save} with the per-id project-access guard.
 *
 * <p>The owned {@code procurement_lines} rows are mapped on the document aggregate
 * ({@code @OneToMany cascade = ALL, orphanRemoval = true}), so saving/deleting a document persists
 * or discards its lines — no separate line DAO is needed for this spec's intake + auto-match scope.
 */
@Repository
public interface ProcurementDocumentDao extends AdminDao<ProcurementDocumentEntity, Long> {

    /**
     * FOR-06-03 Amendment A1 (R18.4) — the ids of documents stuck in a non-terminal processing stage
     * ({@code QUEUED/RASTERIZING/CLEANING/OCR_TEXT/PARSING/MATCHING}) whose last update predates the
     * staleness cutoff. Used by the crash-recovery sweep to re-enqueue (or fail) documents left
     * mid-processing after a backend restart, so none is ever permanently stuck. Terminal
     * ({@code PARSED}/{@code FAILED}) and {@code UPLOADED} (disabled-provider resting) rows are
     * excluded by the explicit status set.
     *
     * @param cutoff the {@code updatedDate} threshold; rows updated before this are considered stale
     * @return the ids of stale non-terminal documents
     */
    @Query("""
            SELECT d.id FROM ProcurementDocumentEntity d
            WHERE d.status IN (
                com.foremen.dao.model.ProcessingStatus.QUEUED,
                com.foremen.dao.model.ProcessingStatus.RASTERIZING,
                com.foremen.dao.model.ProcessingStatus.CLEANING,
                com.foremen.dao.model.ProcessingStatus.OCR_TEXT,
                com.foremen.dao.model.ProcessingStatus.PARSING,
                com.foremen.dao.model.ProcessingStatus.MATCHING)
              AND d.updatedDate < :cutoff
            """)
    List<Long> findStaleProcessingIds(@Param("cutoff") LocalDateTime cutoff);
}
