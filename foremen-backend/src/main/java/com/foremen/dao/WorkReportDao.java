package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.WorkReportEntity;

/**
 * FOR-06-01 (design §4.1) — DAO for {@link WorkReportEntity}. The generic {@link AdminDao} CRUD +
 * {@code JpaSpecificationExecutor} surface is sufficient: {@code WorkReportService} is a
 * {@code ProjectScopedService}, so its LIST reads flow through the inherited specification
 * ({@code addRequiredQuery()} project filter, intersected with the WORKER own-records predicate in
 * the service), and its by-id reads/mutations go through the inherited {@code findById}/{@code save}
 * with the per-id project-access guard. No bespoke finder is required here.
 */
@Repository
public interface WorkReportDao extends AdminDao<WorkReportEntity, Long> {
}
