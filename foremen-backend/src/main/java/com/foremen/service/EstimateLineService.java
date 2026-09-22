package com.foremen.service;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateRecomputeService;
import com.foremen.service.model.EstimateLineServiceExtendedModel;
import com.foremen.service.model.EstimateLineServiceModel;
import com.foremen.service.model.mapper.EstimateLineServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * Project-scoped CRUD service for {@link EstimateLineEntity} (FOR-05-03, Requirement 2).
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code EstimateService}/
 * {@code RoomService}), it implements exactly one CRUD contract — {@link ProjectScopedService} —
 * and never a plain {@link AdminService} and never both. It supplies the standard CRUD plumbing
 * ({@link #getDao()}, {@link #getMapper()}, {@link #getEntityManager()}, {@link #getDaoModelClass()},
 * {@link #getAuditLogDao()}), the single mandatory per-entity override {@link #getProjectIdPath()},
 * and wires {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache#get(Long)}.
 *
 * <p><b>Project scope.</b> A line belongs to an {@code Estimate} which belongs to exactly one
 * {@code Project}, so {@link #getProjectIdPath()} returns the two-hop dotted association path
 * {@code "estimate.project.id"} (R2.1, R9.4).
 *
 * <p><b>DRAFT gate (R7.1, R7.2).</b> Lines are a free-edit resource: {@link #validateCreate},
 * {@link #validateUpdate}, and the {@link #deleteById(Long)} override all resolve the owning
 * {@link EstimateEntity} and run it through {@link DraftGateGuard#assertDraft(EstimateEntity)}
 * before any write, rejecting with {@code 409 error.estimate.locked} once the estimate is past
 * {@code DRAFT}.
 *
 * <p><b>Single-price copy (FOR-05-04 task 17.1, R5.1, R5.2).</b> The {@code EstimateLinePackagePrice}
 * per-package snapshot vertical this service used to call into on
 * {@link #afterCreate(EstimateLineEntity)} has been retired (FOR-05-04, Requirements 8.2, 8.6).
 * On create, {@link #afterCreate(EstimateLineEntity)} unconditionally overwrites
 * {@code EstimateLine.unitPrice} with the line's work item's single {@code WorkPrice.netPrice}
 * (R5.1) and sets {@code EstimateLine.workPrice} to that same row as a provenance FK only — the
 * copied value, not the FK, is the source of truth (mirrors the entity Javadoc contract). No
 * {@code EstimateLinePackagePrice} rows are created; that vertical no longer exists (R5.2). WHEN
 * the work item is unpriced (no {@code WorkPriceEntity} row), the line's {@code unitPrice} stays
 * whatever the caller supplied and {@code workPrice} stays unset — this spec does not fabricate a
 * catalog price for an unpriced work (mirrors the "never fabricate a value" principle, R7.5).
 *
 * <p><b>Recompute (R2.6, R2.7, R8).</b> Every free-edit write (create/update/delete) that changes
 * quantity or price reloads the full estimate graph (lines + their room quantities) and runs
 * {@link EstimateRecomputeService#recomputeEstimate(EstimateEntity)} before returning, so
 * {@code line.quantity}/{@code valueNet} and the owning estimate's totals never go stale.
 */
@Service
public class EstimateLineService
        implements ProjectScopedService<EstimateLineServiceModel, EstimateLineServiceExtendedModel, EstimateLineEntity, Long> {

    /** Message code for a missing entity/reference (404), reused across this service's guards. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private final EstimateLineDao estimateLineDao;
    private final EstimateLineServiceMapper estimateLineServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final EstimateDao estimateDao;
    private final DraftGateGuard draftGateGuard;
    private final EstimateRecomputeService estimateRecomputeService;
    private final WorkPriceDao workPriceDao;

    public EstimateLineService(EstimateLineDao estimateLineDao,
                                EstimateLineServiceMapper estimateLineServiceMapper,
                                ProjectAccessCache projectAccessCache,
                                AuditLogDao auditLogDao,
                                EntityManager entityManager,
                                EstimateDao estimateDao,
                                DraftGateGuard draftGateGuard,
                                EstimateRecomputeService estimateRecomputeService,
                                WorkPriceDao workPriceDao) {
        this.estimateLineDao = estimateLineDao;
        this.estimateLineServiceMapper = estimateLineServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.estimateDao = estimateDao;
        this.draftGateGuard = draftGateGuard;
        this.estimateRecomputeService = estimateRecomputeService;
        this.workPriceDao = workPriceDao;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<EstimateLineEntity, Long> getDao() {
        return estimateLineDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<EstimateLineEntity, EstimateLineServiceModel, EstimateLineServiceExtendedModel> getMapper() {
        return estimateLineServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<EstimateLineEntity> getDaoModelClass() {
        return EstimateLineEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. A line resolves its project boundary through its
     * owning estimate's project, so the project-id path is the two-hop dotted association path
     * {@code "estimate.project.id"} (R2.1, R9.4).
     */
    @Override
    public String getProjectIdPath() {
        return "estimate.project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- DRAFT gate on free-edit writes (R7.1, R7.2) ---

    /**
     * Create-path hook: resolves the target {@code estimateId} and asserts it is still
     * {@code DRAFT} (R7.1, R7.2) before the model is mapped to a new {@link EstimateLineEntity}.
     */
    @Override
    public void validateCreate(EstimateLineServiceExtendedModel model) {
        draftGateGuard.assertDraft(resolveEstimate(model.getEstimateId()));
    }

    /**
     * Update-path hook: asserts the line's owning estimate is still {@code DRAFT} (R7.1, R7.2)
     * before the (now validated) model is copied onto the existing {@link EstimateLineEntity}.
     */
    @Override
    public void validateUpdate(EstimateLineEntity existing, EstimateLineServiceExtendedModel model) {
        draftGateGuard.assertDraft(existing.getEstimate());
    }

    /**
     * Delete-path override: asserts the line's owning estimate is still {@code DRAFT} (R7.1, R7.2)
     * before delegating to the inherited delete, then recomputes the owning estimate's totals so a
     * removed line's contribution is dropped immediately (R8).
     */
    @Override
    public void deleteById(Long id) {
        EstimateLineEntity existing = estimateLineDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, id));
        Long estimateId = existing.getEstimate().getId();
        draftGateGuard.assertDraft(existing.getEstimate());

        ProjectScopedService.super.deleteById(id);

        recomputeAndPersist(estimateId);
    }

    // --- Recompute (R2.6, R2.7, R8) ---

    /**
     * Post-create hook (R5.1, R5.2, R8): copies the line's work item's single catalog price onto
     * {@code entity.unitPrice}/{@code entity.workPrice} (FOR-05-04 task 17.1), then recomputes the
     * owning estimate's lines/totals so the new line's quantity/value and the estimate totals
     * reflect the copied price. The copy happens first so {@code EstimateRecomputeService} — which
     * reads {@code line.getUnitPrice()} to derive {@code valueNet} — sees the catalog price, not
     * whatever placeholder the caller supplied. No {@code EstimateLinePackagePrice} rows are
     * created (that vertical is retired, R5.2).
     */
    @Override
    public void afterCreate(EstimateLineEntity entity) {
        copySingleWorkPrice(entity);
        recomputeAndPersist(entity.getEstimate().getId());
    }

    /**
     * Copies the entity's work item's single {@link WorkPriceEntity#getNetPrice()} onto
     * {@code entity.unitPrice} and sets {@code entity.workPrice} to that row as a provenance FK
     * only (R5.1). The catalog price unconditionally overwrites whatever {@code unitPrice} the
     * caller supplied, mirroring the retired per-package snapshot's original at-add-time copy
     * behavior. WHEN the work item has no {@link WorkPriceEntity} (unpriced), this method leaves
     * {@code unitPrice}/{@code workPrice} untouched rather than fabricating a catalog price.
     */
    private void copySingleWorkPrice(EstimateLineEntity entity) {
        workPriceDao.findByWorkItemId(entity.getWorkItem().getId())
                .ifPresent(workPrice -> {
                    entity.setUnitPrice(workPrice.getNetPrice());
                    entity.setWorkPrice(workPrice);
                });
    }

    /**
     * Resolves the target estimate for the DRAFT gate check, rejecting a missing/dangling
     * {@code estimateId} with a {@code 404 error.entity.not.found} rather than letting a mapper
     * {@code getReference} lazily fail at flush time.
     */
    private EstimateEntity resolveEstimate(Long estimateId) {
        return estimateDao.findById(estimateId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, estimateId));
    }

    /**
     * Reloads the full estimate + lines + room-qty graph for {@code estimateId} and runs
     * {@link EstimateRecomputeService#recomputeEstimate(EstimateEntity)} (R2.6, R2.7, R8). The
     * estimate and its lines are read via managed JPA entities within this transaction, so the
     * mutations {@code recomputeEstimate} applies in place are picked up by dirty checking on
     * flush — no explicit {@code save()} is needed, mirroring {@code EstimateService.afterCreate}'s
     * recompute-then-flush shape.
     */
    private void recomputeAndPersist(Long estimateId) {
        EstimateEntity estimate = estimateDao.findById(estimateId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, estimateId));
        estimateRecomputeService.recomputeEstimate(estimate);
        getEntityManager().flush();
    }
}
