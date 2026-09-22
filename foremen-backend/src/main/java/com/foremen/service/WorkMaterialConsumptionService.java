package com.foremen.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkMaterialConsumptionServiceExtendedModel;
import com.foremen.service.model.WorkMaterialConsumptionServiceModel;
import com.foremen.service.model.mapper.WorkMaterialConsumptionServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * CRUD service for {@link WorkMaterialConsumptionEntity} (FOR-04-19, task 3.2).
 *
 * <p>A GLOBAL admin resource: it implements exactly {@link AdminService} and NOT
 * {@link ProjectScopedService} (Requirement 2.8) — a consumption norm is a work-catalog child row
 * with no project boundary, so no {@code getProjectIdPath()} is supplied (per
 * {@code .kiro/steering/entity-creation-rules.md} step 4).
 *
 * <p><b>Pre-persist normalization.</b> Both create and update run {@link #normalize} through the
 * {@link #validateCreate}/{@link #validateUpdate} hooks (mirroring {@code ConstructionMaterialService}/
 * {@code RoomService.resolveReferences}). Fired inside the generic {@link AdminService} create/update
 * transaction <em>before</em> the mapper turns the model into (or onto) the entity, normalization:
 * <ol>
 *   <li>requires {@code workItemId}, {@code branch}, {@code materialUnitId},
 *       {@code normQty}, and EXACTLY ONE material-type id — a missing required field is rejected with
 *       a field-identifying {@code 400} (Requirements 3.2, 3.3);</li>
 *   <li>real-loads each supplied reference ({@code workItemId}/
 *       {@code materialUnitId} and whichever type id is set), rejecting a dangling id with a
 *       field-identifying {@code 404 error.entity.not.found} (Requirement 3.4);</li>
 *   <li>range-checks {@code normQty} within {@code [0, 99999999.9999]} (incl. negative), rejecting an
 *       out-of-range value with a {@code 400} naming {@code normQty} (Requirement 3.5) — a defensive
 *       re-check of the request-level {@code @DecimalMin}/{@code @DecimalMax};</li>
 *   <li>enforces the XOR + branch-match rule — NEITHER or BOTH type ids set is rejected, and the set
 *       type not matching {@code branch} ({@code construction} requires
 *       {@code constructionMaterialTypeId}, {@code finishing} requires
 *       {@code finishingMaterialTypeId}) is rejected — with a {@code 400}
 *       (Requirement 3.6).</li>
 * </ol>
 * Every rejection throws before persistence, so the enclosing transaction rolls back and nothing is
 * written (Requirements 3.3–3.6). The managed references are attached to the entity by
 * {@link WorkMaterialConsumptionServiceMapper} once normalization has asserted their existence and the
 * XOR + branch-match rule.
 */
@Service
public class WorkMaterialConsumptionService
        implements AdminService<WorkMaterialConsumptionServiceModel, WorkMaterialConsumptionServiceExtendedModel,
        WorkMaterialConsumptionEntity, Long> {

    /** Message code for a missing/dangling reference (404). */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** Message code for a missing required field (400). */
    static final String FIELD_REQUIRED_MESSAGE = "error.work.material.consumption.field.required";

    /** Message code for a {@code normQty} outside {@code [0, 99999999.9999]} (400). */
    static final String NORM_QTY_RANGE_MESSAGE = "error.work.material.consumption.norm.qty.range";

    /** Message code for a violated XOR + branch-match rule (400). */
    static final String MATERIAL_TYPE_BRANCH_MESSAGE = "error.work.material.consumption.material.type.branch";

    /** Inclusive lower bound of a valid {@code normQty} (Requirement 3.5). */
    private static final BigDecimal NORM_QTY_MIN = BigDecimal.ZERO;

    /** Inclusive upper bound of a valid {@code normQty} (Requirement 3.5). */
    private static final BigDecimal NORM_QTY_MAX = new BigDecimal("99999999.9999");

    private final WorkMaterialConsumptionDao dao;
    private final WorkMaterialConsumptionServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final WorkItemDao workItemDao;
    private final MeasurementUnitDao measurementUnitDao;
    private final ConstructionMaterialTypeDao constructionMaterialTypeDao;
    private final MaterialTypeDao materialTypeDao;

    public WorkMaterialConsumptionService(WorkMaterialConsumptionDao dao,
                                          WorkMaterialConsumptionServiceMapper mapper,
                                          AuditLogDao auditLogDao,
                                          EntityManager entityManager,
                                          WorkItemDao workItemDao,
                                          MeasurementUnitDao measurementUnitDao,
                                          ConstructionMaterialTypeDao constructionMaterialTypeDao,
                                          MaterialTypeDao materialTypeDao) {
        this.dao = dao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.workItemDao = workItemDao;
        this.measurementUnitDao = measurementUnitDao;
        this.constructionMaterialTypeDao = constructionMaterialTypeDao;
        this.materialTypeDao = materialTypeDao;
    }

    // --- CRUD plumbing ---

    @Override
    public WorkMaterialConsumptionDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<WorkMaterialConsumptionEntity, WorkMaterialConsumptionServiceModel,
            WorkMaterialConsumptionServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<WorkMaterialConsumptionEntity> getDaoModelClass() {
        return WorkMaterialConsumptionEntity.class;
    }

    // --- Pre-persist normalization ---

    @Override
    public void validateCreate(WorkMaterialConsumptionServiceExtendedModel model) {
        normalize(model);
    }

    @Override
    public void validateUpdate(WorkMaterialConsumptionEntity existing,
                               WorkMaterialConsumptionServiceExtendedModel model) {
        normalize(model);
    }

    /**
     * Shared create/update normalization: requires the mandatory fields, resolves-and-validates every
     * supplied reference, range-checks {@code normQty}, and enforces the XOR + branch-match rule.
     * Every rejection throws a {@link ForemenApiException} before any write, so the enclosing
     * transaction rolls back with nothing persisted.
     */
    private void normalize(WorkMaterialConsumptionServiceExtendedModel model) {
        validateRequiredFields(model);
        validateMaterialTypeBranch(model);
        resolveReferences(model);
        validateNormQty(model);
    }

    /**
     * Requires the mandatory scalar/reference fields present — {@code workItemId},
     * {@code branch}, {@code materialUnitId}, {@code normQty} — rejecting a
     * missing one with a field-identifying {@code 400} (Requirements 3.2, 3.3). The exactly-one
     * material-type requirement is enforced by {@link #validateMaterialTypeBranch}.
     */
    private void validateRequiredFields(WorkMaterialConsumptionServiceExtendedModel model) {
        requirePresent("workItemId", model.getWorkItemId());
        requirePresent("materialUnitId", model.getMaterialUnitId());
        requirePresent("normQty", model.getNormQty());
        if (model.getBranch() == null) {
            throw badRequest(FIELD_REQUIRED_MESSAGE, "branch");
        }
    }

    private void requirePresent(String field, Object value) {
        if (value == null) {
            throw badRequest(FIELD_REQUIRED_MESSAGE, field);
        }
    }

    /**
     * Enforces the XOR + branch-match rule (Requirement 3.6): EXACTLY ONE material-type id must be
     * set (NEITHER or BOTH → reject), and the set type must match {@code branch} — {@code construction}
     * requires {@code constructionMaterialTypeId} (and no {@code finishingMaterialTypeId}),
     * {@code finishing} requires {@code finishingMaterialTypeId} (and no
     * {@code constructionMaterialTypeId}).
     */
    private void validateMaterialTypeBranch(WorkMaterialConsumptionServiceExtendedModel model) {
        boolean hasConstruction = model.getConstructionMaterialTypeId() != null;
        boolean hasFinishing = model.getFinishingMaterialTypeId() != null;

        if (hasConstruction == hasFinishing) {
            // NEITHER or BOTH type ids set.
            throw badRequest(MATERIAL_TYPE_BRANCH_MESSAGE, "materialType");
        }

        ConsumptionBranch branch = model.getBranch();
        if (branch == ConsumptionBranch.construction && !hasConstruction) {
            throw badRequest(MATERIAL_TYPE_BRANCH_MESSAGE, "constructionMaterialTypeId");
        }
        if (branch == ConsumptionBranch.finishing && !hasFinishing) {
            throw badRequest(MATERIAL_TYPE_BRANCH_MESSAGE, "finishingMaterialTypeId");
        }
    }

    /**
     * Real-loads each supplied reference so a dangling id is caught here (Requirement 3.4): the
     * mandatory {@code workItemId}/{@code materialUnitId}, and whichever
     * material-type id is set (existence of exactly one is already asserted by
     * {@link #validateMaterialTypeBranch}). Existence is asserted with a real load rather than a lazy
     * reference.
     */
    private void resolveReferences(WorkMaterialConsumptionServiceExtendedModel model) {
        requireExisting("workItemId", model.getWorkItemId(), id -> workItemDao.findById(id).isPresent());
        requireExisting("materialUnitId", model.getMaterialUnitId(),
                id -> measurementUnitDao.findById(id).isPresent());

        if (model.getConstructionMaterialTypeId() != null) {
            requireExisting("constructionMaterialTypeId", model.getConstructionMaterialTypeId(),
                    id -> constructionMaterialTypeDao.findById(id).isPresent());
        }
        if (model.getFinishingMaterialTypeId() != null) {
            requireExisting("finishingMaterialTypeId", model.getFinishingMaterialTypeId(),
                    id -> materialTypeDao.findById(id).isPresent());
        }
    }

    /** Rejects a dangling reference id with a field-identifying 404. */
    private void requireExisting(String field, Long id, java.util.function.LongPredicate exists) {
        if (id == null || !exists.test(id)) {
            throw notFound(field, id);
        }
    }

    /**
     * Range-checks {@code normQty} within {@code [0, 99999999.9999]} (incl. negative), rejecting an
     * out-of-range value with a {@code 400} naming {@code normQty} (Requirement 3.5).
     */
    private void validateNormQty(WorkMaterialConsumptionServiceExtendedModel model) {
        BigDecimal normQty = model.getNormQty();
        if (normQty == null || normQty.compareTo(NORM_QTY_MIN) < 0 || normQty.compareTo(NORM_QTY_MAX) > 0) {
            throw badRequest(NORM_QTY_RANGE_MESSAGE, "normQty");
        }
    }

    private ForemenApiException notFound(String field, Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, field, id);
    }

    private ForemenApiException badRequest(String messageCode, String field) {
        return new ForemenApiException(HttpStatus.BAD_REQUEST, messageCode, field);
    }

    // ---------------------------------------------------------------------------------------------
    // Audit snapshot override (custom flat snapshot).
    //
    // The generic AdminService audit path serializes the whole WorkMaterialConsumptionEntity with the
    // shared audit ObjectMapper. That would dump the nested @ManyToOne reference entities
    // (workItem/materialUnit/construction+finishingMaterialType) as full nested JSON and
    // risk lazy-init/cycles.
    //
    // Following the FinishingMaterialService/WorkPriceService pattern, this service overrides ONLY the
    // single-entity serialization seam (serializeEntity) so the inherited create/update/delete
    // transactional bodies run unchanged; serializeUpdateAfterSnapshot is left at the AdminService
    // default (serializeEntity(after)), so before AND after share the same flat shape and the audit
    // UI's generic key-by-key diff works. Nested references are flattened to a simple readable name
    // string (PL name, falling back to RU name then code) rather than nested JSON.
    //
    // The computed money range (вилка) and the analog `materials` list are read-time computed, not
    // stored on the entity, and are therefore NOT part of the audit snapshot.
    // ---------------------------------------------------------------------------------------------

    /**
     * Cycle-free, flat single-entity audit snapshot seam. Scalars are emitted verbatim; each nested
     * reference is flattened to a plain readable name (PL name, else RU name, else code), with
     * whichever of {@code constructionMaterialType}/{@code finishingMaterialType} is set collapsed
     * under a single {@code materialType} key. Used for the CREATE-after, DELETE-before, and (via the
     * inherited {@code serializeUpdateAfterSnapshot} default) both the before and after of an UPDATE.
     */
    @Override
    public String serializeEntity(WorkMaterialConsumptionEntity entity) {
        if (entity == null) {
            return null;
        }
        Map<String, Object> snap = buildSnapshot(entity);
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\",\"class\":\"WorkMaterialConsumptionEntity\"}";
        }
    }

    /**
     * Builds the flat, cycle-free snapshot map: scalars plus each nested reference as a simple
     * readable name string (never nested JSON). Exactly one of the two material-type references is
     * set, so it is surfaced under a single {@code materialType} key.
     */
    private Map<String, Object> buildSnapshot(WorkMaterialConsumptionEntity entity) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", entity.getId());
        snap.put("branch", entity.getBranch() == null ? null : entity.getBranch().name());
        snap.put("normQty", entity.getNormQty());
        snap.put("wastePct", entity.getWastePct());
        snap.put("sourceType", entity.getSourceType());
        snap.put("sourceDoc", entity.getSourceDoc());
        snap.put("sourceUrl", entity.getSourceUrl());
        snap.put("sourceRef", entity.getSourceRef());
        snap.put("justificationRU", entity.getJustificationRU());
        snap.put("justificationPL", entity.getJustificationPL());

        snap.put("workItem", workItemName(entity.getWorkItem()));
        snap.put("materialUnit", unitName(entity.getMaterialUnit()));
        snap.put("materialType", materialTypeName(entity));
        return snap;
    }

    private static String workItemName(WorkItemEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getNamePL(), e.getNameRU(), e.getCode());
    }

    private static String unitName(MeasurementUnitEntity e) {
        if (e == null) {
            return null;
        }
        return firstNonBlank(e.getNamePL(), e.getNameRU(), e.getCode());
    }

    /**
     * Whichever of the construction/finishing material-type references is set, flattened to a readable
     * name. Exactly one is non-null per the XOR + branch-match rule, but this is defensive: it prefers
     * the construction type when present, else the finishing type, else {@code null}.
     */
    private static String materialTypeName(WorkMaterialConsumptionEntity entity) {
        ConstructionMaterialTypeEntity construction = entity.getConstructionMaterialType();
        if (construction != null) {
            return firstNonBlank(construction.getNamePL(), construction.getNameRU(), construction.getCode());
        }
        MaterialTypeEntity finishing = entity.getFinishingMaterialType();
        if (finishing != null) {
            return firstNonBlank(finishing.getNamePL(), finishing.getNameRU(), finishing.getCode());
        }
        return null;
    }

    /** First non-null, non-blank value among the candidates, or {@code null} when none qualifies. */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
