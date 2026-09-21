package com.foremen.service;

import java.math.BigDecimal;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateLineRoomQtyDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateLineRoomQtyValidator;
import com.foremen.service.estimate.EstimateRecomputeService;
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

    public EstimateLineRoomQtyService(EstimateLineRoomQtyDao estimateLineRoomQtyDao,
                                       EstimateLineRoomQtyServiceMapper estimateLineRoomQtyServiceMapper,
                                       ProjectAccessCache projectAccessCache,
                                       AuditLogDao auditLogDao,
                                       EntityManager entityManager,
                                       RoomDao roomDao,
                                       EstimateLineRoomQtyValidator estimateLineRoomQtyValidator,
                                       DraftGateGuard draftGateGuard,
                                       EstimateRecomputeService estimateRecomputeService) {
        this.estimateLineRoomQtyDao = estimateLineRoomQtyDao;
        this.estimateLineRoomQtyServiceMapper = estimateLineRoomQtyServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.roomDao = roomDao;
        this.estimateLineRoomQtyValidator = estimateLineRoomQtyValidator;
        this.draftGateGuard = draftGateGuard;
        this.estimateRecomputeService = estimateRecomputeService;
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
}
