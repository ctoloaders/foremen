package com.foremen.dao;

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
}
