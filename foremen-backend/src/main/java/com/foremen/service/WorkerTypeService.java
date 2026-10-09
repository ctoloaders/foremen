package com.foremen.service;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkerTypeServiceExtendedModel;
import com.foremen.service.model.WorkerTypeServiceModel;
import com.foremen.service.model.mapper.WorkerTypeServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-05-06 — CRUD service for the {@link WorkerTypeEntity} cost-tier dictionary (ABAC resource
 * {@code WORKER_TYPES}). A GLOBAL admin dictionary: it implements exactly {@link AdminService} and
 * NOT {@code ProjectScopedService} (worker types are catalog rows with no project boundary), so no
 * {@code getProjectIdPath()} is supplied.
 *
 * <p><b>Pre-persist validation.</b> The {@link #validateCreate}/{@link #validateUpdate} hooks fire
 * inside the generic {@link AdminService} create/update transaction, <em>before</em> the mapper turns
 * the model into (or onto) the entity, so any rejection throws a {@link ForemenApiException} with a
 * localized (PL/RU) message code and the whole write rolls back with nothing persisted:
 * <ul>
 *   <li><b>code</b> — non-blank (also bean-validated on the request) and unique across the dictionary
 *       (create rejects an existing code; update rejects a code used by another row). Since {@code code}
 *       is immutable on update, the update check is a defensive backstop.</li>
 *   <li><b>tierPct &ge; 0</b> — a defensive re-check of the request-level {@code @DecimalMin("0")}.</li>
 *   <li><b>base share in {@code (0, 1]}</b> — for the base tier, {@code tierPct} is the share of the
 *       offer price and must be strictly positive and at most 1.</li>
 *   <li><b>exactly one base tier</b> — a create/update that would produce a second {@code base = true}
 *       row is rejected; an update that would remove the only base row is rejected.</li>
 * </ul>
 */
@Service
public class WorkerTypeService implements AdminService<
        WorkerTypeServiceModel, WorkerTypeServiceExtendedModel, WorkerTypeEntity, Long> {

    /** Message code for a blank {@code code} (400). */
    static final String CODE_REQUIRED_MESSAGE = "error.worker.type.code.required";

    /** Message code for a duplicate {@code code} (409). */
    static final String CODE_DUPLICATE_MESSAGE = "error.worker.type.code.duplicate";

    /** Message code for a negative {@code tierPct} (400). */
    static final String TIER_PCT_RANGE_MESSAGE = "error.worker.type.tierPct.range";

    /** Message code for a base-tier share outside {@code (0, 1]} (400). */
    static final String BASE_SHARE_RANGE_MESSAGE = "error.worker.type.base.share.range";

    /** Message code for a second base tier (400). */
    static final String BASE_DUPLICATE_MESSAGE = "error.worker.type.base.duplicate";

    /** Message code for removing the only base tier (400). */
    static final String BASE_REQUIRED_MESSAGE = "error.worker.type.base.required";

    /**
     * FOR-05-09 Req 14.11: message code (409) for deleting a Worker_Type still referenced by one or
     * more Project_Members. Deactivation (Req 14.10) remains allowed; only hard delete is guarded.
     */
    static final String IN_USE_MESSAGE = "error.worker.type.in.use";

    /** Exclusive lower bound of a valid base-tier share (Requirement 1.5). */
    private static final BigDecimal BASE_SHARE_MIN_EXCLUSIVE = BigDecimal.ZERO;

    /** Inclusive upper bound of a valid base-tier share (Requirement 1.5). */
    private static final BigDecimal BASE_SHARE_MAX_INCLUSIVE = BigDecimal.ONE;

    private final WorkerTypeDao dao;
    private final WorkerTypeServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProjectMemberDao projectMemberDao;

    public WorkerTypeService(WorkerTypeDao dao,
                             WorkerTypeServiceMapper mapper,
                             AuditLogDao auditLogDao,
                             EntityManager entityManager,
                             ProjectMemberDao projectMemberDao) {
        this.dao = dao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectMemberDao = projectMemberDao;
    }

    // --- CRUD plumbing ---

    @Override
    public WorkerTypeDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<WorkerTypeEntity, WorkerTypeServiceModel,
            WorkerTypeServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<WorkerTypeEntity> getDaoModelClass() {
        return WorkerTypeEntity.class;
    }

    // --- Delete guard (FOR-05-09 Req 14.10 / 14.11) ---

    /**
     * FOR-05-09 Req 14.11 — guards the FOR-05-06 Worker_Type hard delete. Before delegating to the
     * generic {@link AdminService#deleteById(Object)} (which writes the DELETE audit row and removes
     * the row), this rejects the deletion of a Worker_Type still referenced by any Project_Member
     * with HTTP 409 and message code {@link #IN_USE_MESSAGE}, leaving the Worker_Type and every
     * referencing Project_Member unchanged. A nonexistent id falls through to the generic 404.
     *
     * <p>Only hard delete is guarded. Deactivation (setting {@code active = false} via the normal
     * {@link #update(Object, Object)} path) stays allowed per Req 14.10 — a deactivated-but-referenced
     * Worker_Type keeps its members, and the Team_API reports it with {@code active = false}.
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        if (projectMemberDao.existsByWorkerTypeId(id)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, IN_USE_MESSAGE, id);
        }
        AdminService.super.deleteById(id);
    }

    // --- Pre-persist validation ---

    @Override
    public void validateCreate(WorkerTypeServiceExtendedModel model) {
        validateCode(model.code());
        if (dao.existsByCode(model.code())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, CODE_DUPLICATE_MESSAGE, model.code());
        }
        validateTierPct(model);
        // Adding a base tier is rejected when any base row already exists (would be two).
        if (model.base() && dao.countByBaseTrue() > 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BASE_DUPLICATE_MESSAGE);
        }
    }

    @Override
    public void validateUpdate(WorkerTypeEntity existing, WorkerTypeServiceExtendedModel model) {
        Long id = existing.getId();
        // code is immutable on update; still guard against a duplicate used by another row.
        String code = model.code() != null ? model.code() : existing.getCode();
        if (dao.existsByCodeAndIdNot(code, id)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, CODE_DUPLICATE_MESSAGE, code);
        }
        validateTierPct(model);

        long otherBaseCount = dao.countByBaseTrueAndIdNot(id);
        if (model.base()) {
            // Turning this row into a base while another base exists would give two.
            if (otherBaseCount > 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, BASE_DUPLICATE_MESSAGE);
            }
        } else {
            // Clearing base on the only base row would leave the dictionary without a base tier.
            if (existing.isBase() && otherBaseCount == 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, BASE_REQUIRED_MESSAGE);
            }
        }
    }

    /** Rejects a blank {@code code} with a 400 (defensive backstop to the request-level {@code @NotBlank}). */
    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, CODE_REQUIRED_MESSAGE);
        }
    }

    /**
     * Rejects a negative {@code tierPct}, and — for the base tier — a share outside {@code (0, 1]}.
     * The base tier's {@code tierPct} is the SHARE of the offer price (e.g. 0.40); non-base tiers'
     * {@code tierPct} is the uplift-on-base and need only be {@code >= 0}.
     */
    private void validateTierPct(WorkerTypeServiceExtendedModel model) {
        BigDecimal tierPct = model.tierPct();
        if (tierPct == null || tierPct.compareTo(BigDecimal.ZERO) < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TIER_PCT_RANGE_MESSAGE);
        }
        if (model.base()
                && (tierPct.compareTo(BASE_SHARE_MIN_EXCLUSIVE) <= 0
                || tierPct.compareTo(BASE_SHARE_MAX_INCLUSIVE) > 0)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BASE_SHARE_RANGE_MESSAGE);
        }
    }
}
