package com.foremen.service;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateLinePackagePriceDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLinePackagePriceEntity;
import com.foremen.dao.model.EstimateLinePackagePriceHistoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.PackagePriceSnapshotService;
import com.foremen.service.model.EstimateLinePackagePriceHistoryServiceModel;
import com.foremen.service.model.EstimateLinePackagePriceServiceExtendedModel;
import com.foremen.service.model.EstimateLinePackagePriceServiceModel;
import com.foremen.service.model.mapper.EstimateLinePackagePriceServiceMapper;
import com.foremen.service.pricing.DiscountCalculator;

import jakarta.persistence.EntityManager;

/**
 * Project-scoped CRUD service for {@link EstimateLinePackagePriceEntity} — the denormalized
 * per-package project price copied from the FOR-04-12b catalog at add-time (FOR-05-03,
 * Requirements 4, 5, 6).
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code EstimateService}), it
 * implements exactly one CRUD contract — {@link ProjectScopedService} — and never a plain
 * {@link AdminService} and never both. It supplies the standard CRUD plumbing
 * ({@link #getDao()}, {@link #getMapper()}, {@link #getEntityManager()},
 * {@link #getDaoModelClass()}, {@link #getAuditLogDao()}), the single mandatory per-entity
 * override {@link #getProjectIdPath()}, and wires {@link #allowedProjectIds(Long)} to
 * {@link ProjectAccessCache#get(Long)}.
 *
 * <p><b>Project scope.</b> A per-package price resolves its owning project through
 * {@code line.estimate.project}, so {@link #getProjectIdPath()} returns
 * {@code "line.estimate.project.id"} (R4.2, R9.4).
 *
 * <p><b>Discount ownership (R5).</b> This service owns the client-editable discount placeholder
 * fields ({@code discountKind}, {@code discountValue}) on an existing per-package price row.
 * {@code originalUnitPrice}/{@code unitPrice}/{@code unpriced} are snapshot/derived fields the
 * mapper (task 5.2) already ignores inbound; on create and on update this service derives the
 * effective {@code unitPrice} via {@link DiscountCalculator#applyDiscount} from the row's
 * {@code originalUnitPrice} and the (possibly just-changed) discount fields, and sets it on the
 * entity before it is persisted (R5.2, R5.3).
 *
 * <p><b>Price-history capture (R6.1, R6.2).</b> After a create or update is persisted (so the
 * row has a non-null id), this service calls
 * {@link PackagePriceSnapshotService#captureHistory(EstimateLinePackagePriceEntity)} to write an
 * append-only history entry reflecting the row's new state.
 *
 * <p><b>DRAFT gate (R7.1, R7.2).</b> Every free-edit create/update/delete resolves the owning
 * estimate via {@code row.getLine().getEstimate()} and asserts
 * {@link DraftGateGuard#assertDraft(com.foremen.dao.model.EstimateEntity)} before any write.
 *
 * <p><b>No estimate-totals recompute here.</b> Per design §6.1, {@code EstimateRecomputeService}
 * derives {@code Estimate.totalNet}/{@code totalVat}/{@code totalGross} exclusively from
 * {@code EstimateLine.valueNet} (itself {@code line.unitPrice × line.quantity}); the per-package
 * project price is a separate, informational per-package view that never feeds the line's
 * {@code unitPrice} or the estimate totals formula. A discount change here therefore never needs
 * to trigger {@code EstimateRecomputeService.recomputeEstimate} — the totals are unaffected by
 * construction (confirmed against design.md §4.3, §6.1 and Requirement 8, which define the totals
 * purely in terms of lines and room quantities, never per-package prices).
 *
 * <p><b>Read-only history (R6.3, resolves to READ).</b> {@link #getHistory(Long)} exposes the
 * per-package price's own change history for the future controller wiring (task 13.2); the
 * handler that calls it is expected to resolve to the {@code READ} ABAC operation since this
 * method performs no write.
 */
@Service
public class EstimateLinePackagePriceService
        implements ProjectScopedService<EstimateLinePackagePriceServiceModel,
        EstimateLinePackagePriceServiceExtendedModel, EstimateLinePackagePriceEntity, Long> {

    private final EstimateLinePackagePriceDao estimateLinePackagePriceDao;
    private final EstimateLinePackagePriceServiceMapper estimateLinePackagePriceServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final DiscountCalculator discountCalculator;
    private final PackagePriceSnapshotService packagePriceSnapshotService;
    private final DraftGateGuard draftGateGuard;

    public EstimateLinePackagePriceService(EstimateLinePackagePriceDao estimateLinePackagePriceDao,
                                            EstimateLinePackagePriceServiceMapper estimateLinePackagePriceServiceMapper,
                                            ProjectAccessCache projectAccessCache,
                                            AuditLogDao auditLogDao,
                                            EntityManager entityManager,
                                            DiscountCalculator discountCalculator,
                                            PackagePriceSnapshotService packagePriceSnapshotService,
                                            DraftGateGuard draftGateGuard) {
        this.estimateLinePackagePriceDao = estimateLinePackagePriceDao;
        this.estimateLinePackagePriceServiceMapper = estimateLinePackagePriceServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.discountCalculator = discountCalculator;
        this.packagePriceSnapshotService = packagePriceSnapshotService;
        this.draftGateGuard = draftGateGuard;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<EstimateLinePackagePriceEntity, Long> getDao() {
        return estimateLinePackagePriceDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<EstimateLinePackagePriceEntity, EstimateLinePackagePriceServiceModel,
            EstimateLinePackagePriceServiceExtendedModel> getMapper() {
        return estimateLinePackagePriceServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<EstimateLinePackagePriceEntity> getDaoModelClass() {
        return EstimateLinePackagePriceEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. A per-package price resolves its project
     * boundary through {@code line.estimate.project}, so the project-id path is the dotted
     * association path {@code "line.estimate.project.id"} (R4.2, R9.4).
     */
    @Override
    public String getProjectIdPath() {
        return "line.estimate.project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Free-edit create/update/delete: DRAFT gate + discount derivation + history capture ---

    /**
     * Creates a per-package price row directly (outside the {@code snapshotForLine} add-time
     * path). Asserts the owning estimate is still {@code DRAFT} (R7.1, R7.2) before delegating to
     * the inherited create, then derives the effective {@code unitPrice} from the persisted row's
     * {@code originalUnitPrice} and discount fields and captures the initial history entry
     * (R5.2, R5.3, R6.1, R6.2).
     */
    @Override
    public EstimateLinePackagePriceServiceExtendedModel create(EstimateLinePackagePriceServiceExtendedModel model) {
        if (model.getLineId() == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", "lineId", model.getLineId());
        }
        draftGateGuard.assertDraft(resolveEstimateByLineId(model.getLineId()));

        EstimateLinePackagePriceServiceExtendedModel created = ProjectScopedService.super.create(model);

        EstimateLinePackagePriceEntity saved = estimateLinePackagePriceDao.findById(created.getId())
                .orElseThrow(() -> notFound(created.getId()));
        applyDiscountAndCaptureHistory(saved);

        return estimateLinePackagePriceServiceMapper.toServiceExtendedModel(saved);
    }

    /**
     * Updates a per-package price's discount placeholder fields (or provenance FK). Asserts the
     * owning estimate is still {@code DRAFT} (R7.1, R7.2) before delegating to the inherited
     * update, then re-derives the effective {@code unitPrice} from the now-updated
     * {@code originalUnitPrice}/discount fields and captures a history entry reflecting the new
     * state (R5.2, R5.3, R6.1, R6.2).
     */
    @Override
    public EstimateLinePackagePriceServiceExtendedModel update(Long id, EstimateLinePackagePriceServiceExtendedModel model) {
        EstimateLinePackagePriceEntity existing = estimateLinePackagePriceDao.findById(id)
                .orElseThrow(() -> notFound(id));
        draftGateGuard.assertDraft(resolveEstimate(existing));

        ProjectScopedService.super.update(id, model);

        EstimateLinePackagePriceEntity saved = estimateLinePackagePriceDao.findById(id)
                .orElseThrow(() -> notFound(id));
        applyDiscountAndCaptureHistory(saved);

        return estimateLinePackagePriceServiceMapper.toServiceExtendedModel(saved);
    }

    /**
     * Deletes a per-package price row. Asserts the owning estimate is still {@code DRAFT}
     * (R7.1, R7.2) before delegating to the inherited delete; the row's history rows cascade at
     * the DB level ({@code package_price_id} is {@code ON DELETE CASCADE}).
     */
    @Override
    public void deleteById(Long id) {
        EstimateLinePackagePriceEntity existing = estimateLinePackagePriceDao.findById(id)
                .orElseThrow(() -> notFound(id));
        draftGateGuard.assertDraft(resolveEstimate(existing));

        ProjectScopedService.super.deleteById(id);
    }

    /**
     * Re-derives the effective {@code unitPrice} from {@code (originalUnitPrice, discountKind,
     * discountValue)} via {@link DiscountCalculator#applyDiscount} and persists it, then captures
     * an append-only history entry for the row's new state (R5.2, R5.3, R6.1, R6.2). {@code row}
     * MUST already be persisted (have a non-null id).
     */
    private void applyDiscountAndCaptureHistory(EstimateLinePackagePriceEntity row) {
        row.setUnitPrice(discountCalculator.applyDiscount(
                row.getOriginalUnitPrice(), row.getDiscountKind(), row.getDiscountValue()));
        estimateLinePackagePriceDao.save(row);
        entityManager.flush();
        packagePriceSnapshotService.captureHistory(row);
    }

    // --- Read-only history (R6.1, R6.3; resolves to READ) ---

    /**
     * Returns the per-package price's own change history, oldest-first, for the read-only history
     * endpoint (R6.3). The controller wiring for this method is task 13.2; the handler that calls
     * it is expected to resolve to the {@code READ} ABAC operation since this performs no write.
     *
     * @param packagePriceId the {@code EstimateLinePackagePrice} id whose history is requested
     * @return every history row for {@code packagePriceId}, oldest-first; never {@code null}
     */
    public List<EstimateLinePackagePriceHistoryServiceModel> getHistory(Long packagePriceId) {
        assertProjectAccess(packagePriceId);
        return packagePriceSnapshotService.findHistory(packagePriceId).stream()
                .map(this::toHistoryServiceModel)
                .toList();
    }

    private EstimateLinePackagePriceHistoryServiceModel toHistoryServiceModel(EstimateLinePackagePriceHistoryEntity history) {
        return new EstimateLinePackagePriceHistoryServiceModel(
                history.getId(),
                history.getPackagePrice() != null ? history.getPackagePrice().getId() : null,
                history.getOriginalUnitPrice(),
                history.getDiscountKind(),
                history.getDiscountValue(),
                history.getUnitPrice(),
                history.getChangedBy(),
                history.getChangedAt());
    }

    // --- helpers ---

    private EstimateEntity resolveEstimate(EstimateLinePackagePriceEntity row) {
        return row.getLine().getEstimate();
    }

    /** Resolves the owning estimate for a not-yet-persisted create, given only the target line id. */
    private EstimateEntity resolveEstimateByLineId(Long lineId) {
        EstimateLineEntity line = entityManager.getReference(EstimateLineEntity.class, lineId);
        return line.getEstimate();
    }

    private ForemenApiException notFound(Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id);
    }
}
