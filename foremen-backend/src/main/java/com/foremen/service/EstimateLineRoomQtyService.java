package com.foremen.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkPackageOverrideDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkPackageOverrideEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateLineRoomQtyValidator;
import com.foremen.service.estimate.EstimateRecomputeService;
import com.foremen.service.formula.FormulaRoomQtyDeriver;
import com.foremen.service.formula.FormulaRoomQtyDeriver.DerivationTrace;
import com.foremen.service.model.EstimateLineRoomQtyServiceExtendedModel;
import com.foremen.service.model.EstimateLineRoomQtyServiceModel;
import com.foremen.service.model.mapper.EstimateLineRoomQtyServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * Project-scoped CRUD service for {@link EstimateLineRoomQtyEntity} — the per-room quantity split
 * of an {@code EstimateLine} (FOR-05-03, Requirement 3).
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code EstimateService}), it
 * implements exactly one CRUD contract — {@link ProjectScopedService} — and never a plain
 * {@link AdminService} and never both. It supplies the standard CRUD plumbing ({@link #getDao()},
 * {@link #getMapper()}, {@link #getEntityManager()}, {@link #getDaoModelClass()},
 * {@link #getAuditLogDao()}), the single mandatory per-entity override
 * {@link #getProjectIdPath()}, and wires {@link #allowedProjectIds(Long)} to
 * {@link ProjectAccessCache#get(Long)}.
 *
 * <p><b>Project scope.</b> A room quantity's owning project is resolved through
 * {@code line.estimate.project}, so {@link #getProjectIdPath()} returns the dotted association
 * path {@code "line.estimate.project.id"} (R3.5, R9.4).
 *
 * <p><b>Write-path validation (R3.2, R3.6, R7).</b> {@link #validateCreate} and
 * {@link #validateUpdate} resolve the referenced {@code line}/{@code room} with a real load
 * (rejecting a missing reference with a field-identifying {@code 404 error.entity.not.found},
 * mirroring {@code RoomService.resolveReferences}), assert the owning estimate is still
 * {@link com.foremen.dao.model.EstimateStatus#DRAFT} via {@link DraftGateGuard#assertDraft}
 * (R7.1, R7.2), and then run {@link EstimateLineRoomQtyValidator#validateRoomQty} against a
 * transient probe entity to enforce the cross-project rule (R3.6) and the non-negative quantity
 * rule (R3.2) <em>before</em> anything is persisted.
 *
 * <p><b>Recompute orchestration (R3.3, R3.4, R8).</b> {@link #afterCreate}, the {@link #update}
 * override, and the {@link #deleteById} override each reload the owning estimate's full
 * line/room-qty graph and run {@link EstimateRecomputeService#recomputeEstimate(EstimateEntity)}
 * after the write, so a room quantity add/change/remove immediately re-derives the owning line's
 * {@code quantity}/{@code valueNet} and the estimate's totals.
 */
@Service
public class EstimateLineRoomQtyService
        implements ProjectScopedService<EstimateLineRoomQtyServiceModel, EstimateLineRoomQtyServiceExtendedModel,
        EstimateLineRoomQtyEntity, Long> {

    /** Message code for a missing {@code lineId}/{@code roomId} reference (404). */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private final EstimateLineRoomQtyDao estimateLineRoomQtyDao;
    private final EstimateLineRoomQtyServiceMapper estimateLineRoomQtyServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final RoomDao roomDao;
    private final EstimateLineRoomQtyValidator estimateLineRoomQtyValidator;
    private final DraftGateGuard draftGateGuard;
    private final EstimateRecomputeService estimateRecomputeService;
    private final EstimateDao estimateDao;
    private final WorkVolumeFormulaDao workVolumeFormulaDao;
    private final WorkPackageOverrideDao workPackageOverrideDao;

    public EstimateLineRoomQtyService(EstimateLineRoomQtyDao estimateLineRoomQtyDao,
                                       EstimateLineRoomQtyServiceMapper estimateLineRoomQtyServiceMapper,
                                       ProjectAccessCache projectAccessCache,
                                       AuditLogDao auditLogDao,
                                       EntityManager entityManager,
                                       RoomDao roomDao,
                                       EstimateLineRoomQtyValidator estimateLineRoomQtyValidator,
                                       DraftGateGuard draftGateGuard,
                                       EstimateRecomputeService estimateRecomputeService,
                                       EstimateDao estimateDao,
                                       WorkVolumeFormulaDao workVolumeFormulaDao,
                                       WorkPackageOverrideDao workPackageOverrideDao) {
        this.estimateLineRoomQtyDao = estimateLineRoomQtyDao;
        this.estimateLineRoomQtyServiceMapper = estimateLineRoomQtyServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.roomDao = roomDao;
        this.estimateLineRoomQtyValidator = estimateLineRoomQtyValidator;
        this.draftGateGuard = draftGateGuard;
        this.estimateRecomputeService = estimateRecomputeService;
        this.estimateDao = estimateDao;
        this.workVolumeFormulaDao = workVolumeFormulaDao;
        this.workPackageOverrideDao = workPackageOverrideDao;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<EstimateLineRoomQtyEntity, Long> getDao() {
        return estimateLineRoomQtyDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<EstimateLineRoomQtyEntity, EstimateLineRoomQtyServiceModel,
            EstimateLineRoomQtyServiceExtendedModel> getMapper() {
        return estimateLineRoomQtyServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<EstimateLineRoomQtyEntity> getDaoModelClass() {
        return EstimateLineRoomQtyEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. A room quantity resolves its project boundary
     * through {@code line.estimate.project}, so the project-id path is the dotted association
     * path {@code "line.estimate.project.id"} (R3.5, R9.4).
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

    // --- Write-path validation (R3.2, R3.6, R7) ---

    /**
     * Create-path hook: resolves {@code line}/{@code room}, asserts the owning estimate is still
     * DRAFT, then runs the cross-project + non-negative validation — all before the mapper turns
     * the model into a new {@link EstimateLineRoomQtyEntity}.
     */
    @Override
    public void validateCreate(EstimateLineRoomQtyServiceExtendedModel model) {
        EstimateLineEntity line = resolveLine(model.getLineId());
        RoomEntity room = resolveRoom(model.getRoomId());
        draftGateGuard.assertDraft(line.getEstimate());
        estimateLineRoomQtyValidator.validateRoomQty(probe(line, room, model.getQuantity()));
    }

    /**
     * Update-path hook: re-resolves {@code line}/{@code room} from the (possibly changed) model,
     * asserts DRAFT on the owning estimate, then re-runs the cross-project + non-negative
     * validation before the mapper copies the update onto {@code existing}.
     */
    @Override
    public void validateUpdate(EstimateLineRoomQtyEntity existing, EstimateLineRoomQtyServiceExtendedModel model) {
        Long lineId = model.getLineId() != null ? model.getLineId() : existing.getLine().getId();
        Long roomId = model.getRoomId() != null ? model.getRoomId() : existing.getRoom().getId();
        EstimateLineEntity line = resolveLine(lineId);
        RoomEntity room = resolveRoom(roomId);
        draftGateGuard.assertDraft(line.getEstimate());
        estimateLineRoomQtyValidator.validateRoomQty(probe(line, room, model.getQuantity()));
    }

    // --- Recompute orchestration (R3.3, R3.4, R8) ---

    /** Post-create hook: recompute the owning estimate now that a room quantity was added. */
    @Override
    public void afterCreate(EstimateLineRoomQtyEntity entity) {
        recomputeOwningEstimate(entity.getLine().getId());
    }

    /**
     * Update-by-id override: enforce project access via the inherited default, then recompute the
     * owning estimate now that the room quantity changed. Overriding (rather than adding an
     * {@code afterUpdate} hook, which {@link AdminService} does not provide) mirrors the
     * {@code deleteById}-override convention used elsewhere in the codebase (e.g.
     * {@code FinishingMaterialService}) for post-write side effects.
     */
    @Override
    public EstimateLineRoomQtyServiceExtendedModel update(Long id, EstimateLineRoomQtyServiceExtendedModel model) {
        EstimateLineRoomQtyServiceExtendedModel updated = ProjectScopedService.super.update(id, model);
        recomputeOwningEstimate(updated.getLineId());
        return updated;
    }

    /**
     * Delete-by-id override: enforce project access + DRAFT gate before removal, delegate to the
     * inherited delete, then recompute the owning estimate now that the room quantity was removed.
     */
    @Override
    public void deleteById(Long id) {
        EstimateLineRoomQtyEntity existing = estimateLineRoomQtyDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, id));
        Long lineId = existing.getLine().getId();
        draftGateGuard.assertDraft(existing.getLine().getEstimate());

        ProjectScopedService.super.deleteById(id);

        recomputeOwningEstimate(lineId);
    }

    // --- helpers ---

    /** Real-load resolution of the {@code line} reference; a missing id is a field-identifying 404. */
    private EstimateLineEntity resolveLine(Long lineId) {
        EstimateLineEntity line = lineId == null ? null : entityManager.find(EstimateLineEntity.class, lineId);
        if (line == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "lineId", lineId);
        }
        return line;
    }

    /** Real-load resolution of the {@code room} reference; a missing id is a field-identifying 404. */
    private RoomEntity resolveRoom(Long roomId) {
        RoomEntity room = roomId == null ? null : roomDao.findById(roomId).orElse(null);
        if (room == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "roomId", roomId);
        }
        return room;
    }

    /** A transient (unpersisted) probe entity carrying only what {@code validateRoomQty} reads. */
    private EstimateLineRoomQtyEntity probe(EstimateLineEntity line, RoomEntity room, BigDecimal quantity) {
        EstimateLineRoomQtyEntity probe = new EstimateLineRoomQtyEntity();
        probe.setLine(line);
        probe.setRoom(room);
        probe.setQuantity(quantity);
        return probe;
    }

    /**
     * Reloads the owning {@link EstimateEntity} (with its lines and room quantities) via the
     * just-touched line and runs {@link EstimateRecomputeService#recomputeEstimate}, then flushes
     * so the derived totals are persisted in the same transaction as the room-qty write.
     */
    private void recomputeOwningEstimate(Long lineId) {
        EstimateLineEntity line = entityManager.find(EstimateLineEntity.class, lineId);
        EstimateEntity estimate = line.getEstimate();
        estimateRecomputeService.recomputeEstimate(estimate);
        entityManager.flush();
    }

    // --- Formula-driven derivation (FOR-05-04 task 20.1, R4.2, R5.2, R5.3, R5.4, R5.5) ---

    /**
     * Derives (creates/updates) {@link EstimateLineRoomQtyEntity#getQuantity()} for every
     * estimate line in {@code room}'s owning project's estimate whose work item has an
     * applicable formula (a package override formula for {@code activePackageId} if present,
     * else the work's default volume formula — the §6.5 {@code applicableFormula} precedence
     * rule, R4.2/R5.3), leaving every other line's room quantity — including any line whose work
     * has no applicable formula — exactly as hand-entered (R5.4).
     *
     * <p>This is the wiring point connecting {@code EstimateLineService}'s single-price copy to
     * {@link FormulaRoomQtyDeriver} (task 20.1): design.md §6.4/§6.5 define the pure derivation
     * algorithm but do not specify a REST trigger for it, so this is exposed as a plain service
     * method a caller (a future controller action, a room-save hook, or task 20.3's end-to-end
     * test) invokes explicitly with the room and the package context to derive for — the least
     * invasive wiring that still satisfies "connect EstimateLineService -&gt; FormulaRoomQtyDeriver"
     * without inventing an endpoint design.md does not specify.
     *
     * <p>Every work present in the room (i.e. every work referenced by one of the room's
     * project's estimate lines) that has ANY formula (default or override, whether or not it
     * turns out to be the winning one for {@code activePackageId}) is included in the reference
     * graph passed to {@link FormulaRoomQtyDeriver#deriveForRoom}, so a formula's cross-work
     * {@code WorkRef} to a same-room work resolves correctly (R3.1, R3.2) even when the
     * referenced work's own formula is not the one ultimately applied for this call (its
     * resolved value is still in the graph the referencing formula observes).
     *
     * @param roomId          the room to derive room quantities for
     * @param activePackageId the {@link OfferPackageEntity} id of the estimate's active package
     *                        context, or {@code null} if there is none (package override formulas
     *                        are then never applicable and only default formulas are used)
     * @return the {@link DerivationTrace} for every work ref that was derived (empty if no line
     *         in the room has an applicable formula)
     */
    @Transactional
    public Map<String, DerivationTrace> deriveRoomQuantitiesForRoom(Long roomId, Long activePackageId) {
        RoomEntity room = resolveRoom(roomId);
        EstimateEntity estimate = estimateDao.findByProjectId(room.getProject().getId())
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", room.getProject().getId()));
        draftGateGuard.assertDraft(estimate);

        // Every estimate line whose work item carries ANY formula (default or override) — the
        // room-scoped reference-graph universe (R3.1, R3.2) — keyed by WorkItem.code.
        Map<String, EstimateLineEntity> lineByWorkRef = new HashMap<>();
        Map<String, WorkVolumeFormulaEntity> defaultFormulaByWorkRef = new HashMap<>();
        Map<String, WorkPackageOverrideEntity> overrideByWorkRef = new HashMap<>();

        for (EstimateLineEntity line : estimate.getLines()) {
            String code = line.getWorkItem().getCode();
            if (code == null || code.isBlank()) {
                continue;
            }
            Long workItemId = line.getWorkItem().getId();
            WorkVolumeFormulaEntity defaultFormula = workVolumeFormulaDao.findByWorkItemId(workItemId).orElse(null);
            WorkPackageOverrideEntity override = activePackageId == null ? null
                    : workPackageOverrideDao.findByWorkItemIdAndOfferPackageId(workItemId, activePackageId)
                            .orElse(null);
            if (defaultFormula == null && (override == null || override.getOverrideParsedAst() == null)) {
                continue; // no formula at all for this work -> hand entry stays (R5.4)
            }
            lineByWorkRef.put(code, line);
            if (defaultFormula != null) {
                defaultFormulaByWorkRef.put(code, defaultFormula);
            }
            if (override != null) {
                overrideByWorkRef.put(code, override);
            }
        }

        if (lineByWorkRef.isEmpty()) {
            return Map.of();
        }

        // §6.5 applicableFormula precedence per work ref: override wins, else default (R4.2, R5.3).
        Map<String, FormulaAst> applicableFormulas = new HashMap<>();
        Map<String, String> applicableFormulaSources = new HashMap<>();
        for (Map.Entry<String, EstimateLineEntity> entry : lineByWorkRef.entrySet()) {
            String code = entry.getKey();
            FormulaAst applicable = FormulaRoomQtyDeriver.resolveApplicableFormula(
                    overrideByWorkRef.get(code), defaultFormulaByWorkRef.get(code));
            if (applicable == null) {
                continue;
            }
            applicableFormulas.put(code, applicable);
            WorkPackageOverrideEntity override = overrideByWorkRef.get(code);
            if (override != null && override.getOverrideParsedAst() != null) {
                applicableFormulaSources.put(code, override.getOverrideSourceText());
            } else {
                applicableFormulaSources.put(code, defaultFormulaByWorkRef.get(code).getSourceText());
            }
        }

        Map<String, DerivationTrace> traces =
                FormulaRoomQtyDeriver.deriveForRoom(room, applicableFormulas, applicableFormulaSources);

        for (Map.Entry<String, DerivationTrace> entry : traces.entrySet()) {
            EstimateLineEntity line = lineByWorkRef.get(entry.getKey());
            upsertDerivedRoomQty(line, room, entry.getValue().resolvedValue());
        }

        if (!traces.isEmpty()) {
            estimateRecomputeService.recomputeEstimate(estimate);
            entityManager.flush();
        }

        return traces;
    }

    /**
     * Creates or updates the {@code (line, room)} {@link EstimateLineRoomQtyEntity} with a
     * formula-derived {@code quantity}. A work with no applicable formula never reaches this
     * method (its hand-entered row, if any, is left untouched — R5.4).
     */
    private void upsertDerivedRoomQty(EstimateLineEntity line, RoomEntity room, BigDecimal derivedQuantity) {
        EstimateLineRoomQtyEntity existing = null;
        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
            if (roomQty.getRoom() != null && roomQty.getRoom().getId().equals(room.getId())) {
                existing = roomQty;
                break;
            }
        }
        if (existing != null) {
            existing.setQuantity(derivedQuantity);
            estimateLineRoomQtyDao.save(existing);
        } else {
            EstimateLineRoomQtyEntity created = new EstimateLineRoomQtyEntity();
            created.setLine(line);
            created.setRoom(room);
            created.setQuantity(derivedQuantity);
            estimateLineRoomQtyDao.save(created);
            // EstimateRecomputeService.recomputeEstimate reads line.getRoomQtys() — a
            // newly-persisted row is invisible to that already-loaded in-memory collection
            // unless added here explicitly, so the subsequent recompute (called right after this
            // method returns, still in the same transaction) sees the freshly derived quantity.
            line.getRoomQtys().add(created);
        }
    }
}
