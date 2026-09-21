package com.foremen.service;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.EstimateRecomputeService;
import com.foremen.service.model.EstimateServiceExtendedModel;
import com.foremen.service.model.EstimateServiceModel;
import com.foremen.service.model.mapper.EstimateServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * Project-scoped CRUD service for {@link EstimateEntity} — the single {@code Estimate} per
 * {@code Project} (FOR-05-03, Requirement 1).
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code RoomService}), it implements
 * exactly one CRUD contract — {@link ProjectScopedService} — and never a plain {@link AdminService}
 * and never both. It supplies the standard CRUD plumbing ({@link #getDao()}, {@link #getMapper()},
 * {@link #getEntityManager()}, {@link #getDaoModelClass()}, {@link #getAuditLogDao()}), the single
 * mandatory per-entity override {@link #getProjectIdPath()}, and wires
 * {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache#get(Long)}.
 *
 * <p><b>1:1 with Project.</b> An {@code Estimate} belongs to exactly one {@code Project} through its
 * {@code @ManyToOne project} association (UNIQUE at the DB level), so {@link #getProjectIdPath()}
 * returns {@code "project.id"} (R1.1, R9.4).
 *
 * <p><b>Create-time defaults (R1.2, R1.3).</b> {@link #validateCreate(EstimateServiceExtendedModel)}
 * defaults {@code currencyId} to the seeded {@code PLN} currency and {@code status} to
 * {@link EstimateStatus#DRAFT} when the caller leaves them unset, before the mapper turns the model
 * into a new entity.
 *
 * <p><b>Single-estimate invariant (R1.5, R1.6).</b> {@link #getOrCreateForProject(Long)} is the
 * create-or-resolve upsert path: it returns the project's existing estimate via
 * {@link EstimateDao#findByProjectId(Long)} if one exists, otherwise creates exactly one.
 * {@link #validateCreate(EstimateServiceExtendedModel)} additionally rejects an <em>explicit</em>
 * second {@code create()} call for a project that already has an estimate with a clean
 * {@code 409 error.estimate.already.exists} rather than letting a raw DB unique-constraint
 * violation surface; the DB constraint remains the ultimate backstop.
 *
 * <p><b>Totals recompute orchestration.</b> {@link #afterCreate(EstimateEntity)} runs
 * {@link EstimateRecomputeService#recomputeEstimate(EstimateEntity)} and persists the (all-zero,
 * since a freshly created estimate has no lines yet) derived totals, for consistency with the
 * later line/room-qty/price write paths (tasks 12.3-12.5) that trigger the same recompute on their
 * own writes.
 */
@Service
public class EstimateService
        implements ProjectScopedService<EstimateServiceModel, EstimateServiceExtendedModel, EstimateEntity, Long> {

    /** Message code for an explicit second create attempt on an already-estimated project (409, R1.6). */
    static final String ALREADY_EXISTS_MESSAGE = "error.estimate.already.exists";

    /** The seeded default currency code applied when a caller omits {@code currencyId} (R1.2). */
    private static final String DEFAULT_CURRENCY_CODE = "PLN";

    /** Message code surfaced if the {@code PLN} currency seed is somehow missing (500, defensive). */
    private static final String DEFAULT_CURRENCY_MISSING_MESSAGE = "error.currency.default.missing";

    private final EstimateDao estimateDao;
    private final EstimateServiceMapper estimateServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final CurrencyDao currencyDao;
    private final EstimateRecomputeService estimateRecomputeService;

    public EstimateService(EstimateDao estimateDao,
                            EstimateServiceMapper estimateServiceMapper,
                            ProjectAccessCache projectAccessCache,
                            AuditLogDao auditLogDao,
                            EntityManager entityManager,
                            CurrencyDao currencyDao,
                            EstimateRecomputeService estimateRecomputeService) {
        this.estimateDao = estimateDao;
        this.estimateServiceMapper = estimateServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.currencyDao = currencyDao;
        this.estimateRecomputeService = estimateRecomputeService;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<EstimateEntity, Long> getDao() {
        return estimateDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<EstimateEntity, EstimateServiceModel, EstimateServiceExtendedModel> getMapper() {
        return estimateServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<EstimateEntity> getDaoModelClass() {
        return EstimateEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. An estimate resolves its project boundary through
     * its {@code @ManyToOne project} association, so the project-id path is the dotted association
     * path {@code "project.id"} (R1.1, R9.4).
     */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Create-time defaults + single-estimate invariant (R1.2, R1.3, R1.6) ---

    /**
     * Create-path hook: rejects an explicit second create for an already-estimated project with a
     * clean {@code 409} (R1.6), then defaults {@code currencyId}/{@code status} (R1.2, R1.3) before
     * the model is mapped to a new {@link EstimateEntity}. Callers that want create-or-resolve
     * semantics should use {@link #getOrCreateForProject(Long)} instead of calling {@code create}
     * directly.
     */
    @Override
    public void validateCreate(EstimateServiceExtendedModel model) {
        if (model.getProjectId() != null && estimateDao.findByProjectId(model.getProjectId()).isPresent()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ALREADY_EXISTS_MESSAGE);
        }
        applyCreateDefaults(model);
    }

    /**
     * Totals recompute orchestration (R8): a freshly created estimate has no lines yet, so this is
     * trivially all-zero, but it is wired in here for consistency with the later line/room-qty/price
     * write paths (tasks 12.3-12.5) that trigger the same recompute on their own writes.
     */
    @Override
    public void afterCreate(EstimateEntity entity) {
        estimateRecomputeService.recomputeEstimate(entity);
        getEntityManager().flush();
    }

    /**
     * Returns the project's estimate, creating exactly one if absent (R1.5). Reuses the same
     * {@link EstimateDao#findByProjectId(Long)} lookup {@link #validateCreate} checks, so a
     * concurrent/second call resolves to the existing row rather than racing a duplicate — the
     * DB-level UNIQUE constraint on {@code estimates.project_id} is the ultimate backstop.
     *
     * @param projectId the owning project's id
     * @return the project's existing estimate, or a newly created one defaulted to PLN/DRAFT
     */
    @Transactional
    public EstimateServiceExtendedModel getOrCreateForProject(Long projectId) {
        return estimateDao.findByProjectId(projectId)
                .map(estimateServiceMapper::toServiceExtendedModel)
                .orElseGet(() -> {
                    EstimateServiceExtendedModel model = new EstimateServiceExtendedModel();
                    model.setProjectId(projectId);
                    return create(model);
                });
    }

    /** Defaults {@code currencyId} to PLN and {@code status} to DRAFT when the caller omits them. */
    private void applyCreateDefaults(EstimateServiceExtendedModel model) {
        if (model.getCurrencyId() == null) {
            model.setCurrencyId(resolveDefaultCurrencyId());
        }
        if (model.getStatus() == null) {
            model.setStatus(EstimateStatus.DRAFT);
        }
    }

    /** Resolves the seeded {@code PLN} currency's id (R1.2). */
    private Long resolveDefaultCurrencyId() {
        CurrencyEntity pln = currencyDao.findByCode(DEFAULT_CURRENCY_CODE)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, DEFAULT_CURRENCY_MISSING_MESSAGE));
        return pln.getId();
    }
}
