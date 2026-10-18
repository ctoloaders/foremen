package com.foremen.dao;

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
}
