package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.estimate.CellFactsResolver;
import com.foremen.service.estimate.CellMetaInput;
import com.foremen.service.estimate.CellMetaOverlay;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.EstimateAssignmentService.EditKind;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.estimate.LabourPriceAdjustmentScope;
import com.foremen.service.roomsimport.JobActionLog;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-04 rework (Requirements 24.1 to 24.7, 24.12, 6.2, 6.4; design R3) — applies one {@code assign_work}
 * call to the <b>live</b> estimate and commits it.
 *
 * <p>One call is one transaction ({@link Propagation#REQUIRES_NEW}); on commit the cells, quantities,
 * material lines and the {@code ai_meta} annotations are visible to every reader. In that transaction it:
 * <ol>
 *   <li>validates the command and the owning job (typed {@link AssignRejected}, nothing written);</li>
 *   <li>runs the normal assign path of {@link EstimateAssignmentService} for each room, so the volume
 *       resolver, the frozen labour price, line numbering and the {@code EstimateMaterialSeeder} behave
 *       exactly as for a manual assign (materials are seeded for every cell, with the job's package);</li>
 *   <li>stores the model volume as a manual override only when it differs from the formula volume by more
 *       than {@code volume-tolerance-pct}; otherwise the formula volume applies (an earlier override of the
 *       same cell is cleared: last call wins);</li>
 *   <li>applies a labour price coefficient only when one is stated in the command;</li>
 *   <li>writes the {@code CellAiMeta} of each cell through {@link CellMetaOverlay} (engine facts from
 *       {@link CellFactsResolver}, texts from the command);</li>
 *   <li>appends the action-log entries and increments {@code works_done}.</li>
 * </ol>
 * Repeating an existing {@code (work, room)} updates that cell and never creates a second line or cell.
 */
@Service
@RequiredArgsConstructor
public class AssignWorkService {

    /** Origin of the cells written by the job. */
    public static final String ORIGIN_JOB = "JOB";

    private static final Pattern SOURCE_PAGE = Pattern.compile("^(.*?)(?:,\\s*)?p\\.(\\d+)$");

    private final EstimateAssignmentService assignmentService;
    private final AiProjectLockGuard lockGuard;
    private final CellFactsResolver factsResolver;
    private final CellMetaOverlay metaOverlay;
    private final JobActionLog actionLog;
    private final RoomsImportJobDao jobDao;
    private final EstimateDao estimateDao;
    private final WorkItemDao workItemDao;
    private final WorkPriceDao workPriceDao;
    private final RoomDao roomDao;
    private final JdbcTemplate jdbc;
    private final EstimateAiProperties properties;

    /**
     * Applies and commits one assignment.
     *
     * @throws AssignRejected for an expected rejection; the model gets the code, the job continues
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssignWorkResult assign(AiJobContext ctx, AssignWorkCommand cmd) {
        Validated v = validate(ctx, cmd);
        Long projectId = ctx.projectId();
        String packageCode = ctx.packageCode();

        Map<Long, Boolean> existedBefore = new LinkedHashMap<>();
        EstimateEntity before = estimateDao.findByProjectId(projectId).orElse(null);
        for (RoomEntity room : v.rooms()) {
            existedBefore.put(room.getId(), findCell(before, v.work().getId(), room.getId()) != null);
        }

        List<StagedEdit> edits = new ArrayList<>();
        for (RoomEntity room : v.rooms()) {
            edits.add(StagedEdit.assign(v.work().getId(), room.getId(), packageCode));
        }
        Map<Long, Boolean> overridden = new LinkedHashMap<>();
        for (RoomEntity room : v.rooms()) {
            CellFactsResolver.Facts facts = factsResolver.facts(v.work(), room, packageCode);
            BigDecimal formula = facts == null ? null : facts.formulaVolume();
            boolean override = needsOverride(cmd.volume(), formula, properties.volumeTolerancePct());
            overridden.put(room.getId(), override);
            if (override) {
                edits.add(new StagedEdit(EditKind.SET_QUANTITY, v.work().getId(), room.getId(), null, null, null,
                        null, null, packageCode, cmd.volume()));
            } else if (existedBefore.get(room.getId())) {
                edits.add(new StagedEdit(EditKind.CLEAR_QUANTITY, v.work().getId(), room.getId(), null, null, null,
                        null, null, packageCode, null));
            }
        }
        for (AssignWorkCommand.Coefficient c : cmd.coefficients()) {
            if (validCoefficient(c)) {
                edits.add(StagedEdit.adjustLabourPrice(c.scope(),
                        c.scope() == LabourPriceAdjustmentScope.PROJECT ? null : c.targetId(), c.pct()));
            }
        }

        EstimateEntity estimate = assignmentService.applyAiAssignments(projectId, edits, packageCode);

        List<CellMetaInput> metas = new ArrayList<>();
        for (RoomEntity room : v.rooms()) {
            metas.add(meta(v.work(), room.getId(), cmd, overridden.get(room.getId())));
        }
        metaOverlay.apply(estimate, projectId, metas);

        List<AssignWorkResult.Cell> cells = new ArrayList<>();
        for (RoomEntity room : v.rooms()) {
            EstimateLineRoomQtyEntity cell = findCell(estimate, v.work().getId(), room.getId());
            cells.add(new AssignWorkResult.Cell(room.getId(), cell == null ? null : cell.getId(),
                    !existedBefore.get(room.getId()), cell == null ? null : cell.getQuantity(),
                    cell != null && cell.isVolumeOverridden(),
                    cell == null || cell.getMaterials() == null ? 0 : cell.getMaterials().size()));
        }
        AssignWorkResult result = new AssignWorkResult(v.work().getId(), v.work().getCode(), cells);

        // Params use the names of the frontend action templates: room, workCode, workName, volume, unit, count.
        String workName = work(v.work());
        String unit = v.work().getUnit() == null ? null : v.work().getUnit().getCode();
        for (int i = 0; i < v.rooms().size(); i++) {
            RoomEntity room = v.rooms().get(i);
            AssignWorkResult.Cell cell = cells.get(i);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("room", roomLabel(room));
            params.put("workCode", v.work().getCode());
            params.put("workName", workName);
            if (cell.volume() != null) {
                params.put("volume", cell.volume().stripTrailingZeros().toPlainString());
            }
            if (unit != null) {
                params.put("unit", unit);
            }
            actionLog.append(ctx.jobId(), RoomsImportStatus.ASSIGNING, JobActionLog.WORK_ASSIGNED, params);
        }
        if (result.materialLines() > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workCode", v.work().getCode());
            m.put("workName", workName);
            m.put("count", result.materialLines());
            actionLog.append(ctx.jobId(), RoomsImportStatus.ASSIGNING, JobActionLog.MATERIALS_SEEDED, m);
        }
        jdbc.update("UPDATE rooms_import_jobs SET works_done = coalesce(works_done, 0) + 1 WHERE id = ?",
                ctx.jobId());
        return result;
    }

    private static String work(WorkItemEntity w) {
        return w.getNamePL() != null ? w.getNamePL() : w.getNameRU();
    }

    /** Human label of a room for the action log: its label, else the room type name. */
    public static String roomLabel(RoomEntity room) {
        if (room.getLabel() != null && !room.getLabel().isBlank()) {
            return room.getLabel();
        }
        return room.getRoomType() == null ? String.valueOf(room.getId()) : room.getRoomType().getNameRU();
    }

    // ------------------------------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------------------------------

    private record Validated(WorkItemEntity work, List<RoomEntity> rooms) {
    }

    private Validated validate(AiJobContext ctx, AssignWorkCommand cmd) {
        if (ctx == null || ctx.jobId() == null || ctx.projectId() == null || cmd == null) {
            throw rejected(WarningCodes.EDIT_REJECTED, "reason", "JOB_NOT_ACTIVE");
        }
        RoomsImportJobEntity job = jobDao.findById(ctx.jobId()).orElse(null);
        if (job == null || job.getStatus() == null || job.getStatus().isTerminal()
                || !"ESTIMATE".equals(job.getMode()) || job.getProject() == null
                || !ctx.projectId().equals(job.getProject().getId())) {
            throw rejected(WarningCodes.EDIT_REJECTED, "reason", "JOB_NOT_ACTIVE");
        }
        // The job's own context passes the AI project lock; any other active job is a 409.
        lockGuard.assertUnlocked(ctx.projectId(), ctx);

        if (cmd.rationale() == null || cmd.rationale().isBlank()) {
            throw rejected(WarningCodes.EDIT_REJECTED, "reason", WarningCodes.RATIONALE_MISSING);
        }
        if (cmd.volume() != null && cmd.volume().signum() < 0) {
            throw rejected(WarningCodes.EDIT_REJECTED, "reason", "NEGATIVE_VOLUME");
        }
        WorkItemEntity work = cmd.workItemId() == null ? null : workItemDao.findById(cmd.workItemId()).orElse(null);
        if (work == null || !work.isActive()) {
            throw rejected(WarningCodes.UNKNOWN_CODE, "workItemId", cmd.workItemId());
        }
        String unitCode = work.getUnit() != null ? work.getUnit().getCode() : null;
        if (EstimateAiContextLoader.isPercentage(unitCode)) {
            throw rejected(WarningCodes.UNSUPPORTED_UNIT, "workItemId", work.getId());
        }
        WorkPriceEntity price = workPriceDao.findByWorkItemId(work.getId()).orElse(null);
        if (price == null || price.getNetPrice() == null || price.getNetPrice().signum() <= 0) {
            throw rejected(WarningCodes.NO_PRICE, "workItemId", work.getId());
        }
        List<Long> roomIds = new ArrayList<>(new LinkedHashSet<>(
                cmd.roomIds().stream().filter(Objects::nonNull).toList()));
        if (roomIds.isEmpty()) {
            throw rejected(WarningCodes.EDIT_REJECTED, "reason", "NO_ROOMS");
        }
        List<RoomEntity> rooms = new ArrayList<>();
        for (Long roomId : roomIds) {
            RoomEntity room = roomDao.findById(roomId).orElse(null);
            if (room == null || room.getProject() == null || !ctx.projectId().equals(room.getProject().getId())) {
                throw rejected(WarningCodes.EDIT_REJECTED, "reason", WarningCodes.UNKNOWN_ROOM, "roomId", roomId);
            }
            rooms.add(room);
        }
        return new Validated(work, rooms);
    }

    private static AssignRejected rejected(String code, Object... kv) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            params.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new AssignRejected(code, params);
    }

    // ------------------------------------------------------------------------------------------------
    // Pure rules
    // ------------------------------------------------------------------------------------------------

    /**
     * True when the model volume must be stored as a manual override (Requirements 6.2, 24.6): the volume is
     * positive and either the formula gives nothing (zero or unknown) or the relative difference exceeds the
     * tolerance in percent.
     */
    static boolean needsOverride(BigDecimal modelVolume, BigDecimal formulaVolume, double tolerancePct) {
        if (modelVolume == null || modelVolume.signum() <= 0) {
            return false;
        }
        if (formulaVolume == null || formulaVolume.signum() == 0) {
            return true;
        }
        BigDecimal diffPct = modelVolume.subtract(formulaVolume).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(formulaVolume.abs(), 6, RoundingMode.HALF_UP);
        return diffPct.compareTo(BigDecimal.valueOf(tolerancePct)) > 0;
    }

    /** A coefficient is applied only when it is complete and non-zero (Requirement 6.4: stated only). */
    static boolean validCoefficient(AssignWorkCommand.Coefficient c) {
        if (c == null || c.scope() == null || c.pct() == null || c.pct().signum() == 0) {
            return false;
        }
        return c.scope() == LabourPriceAdjustmentScope.PROJECT || c.targetId() != null;
    }

    static CellMetaInput meta(WorkItemEntity work, Long roomId, AssignWorkCommand cmd, boolean override) {
        String file = null;
        Integer page = null;
        if (cmd.source() != null && !cmd.source().isBlank()) {
            Matcher m = SOURCE_PAGE.matcher(cmd.source().trim());
            if (m.matches()) {
                file = m.group(1).isBlank() ? null : m.group(1).trim();
                try {
                    page = Integer.valueOf(m.group(2));
                } catch (NumberFormatException e) {
                    page = null;
                }
            } else {
                file = cmd.source().trim();
            }
        }
        boolean priceStated = cmd.coefficients().stream().anyMatch(AssignWorkService::validCoefficient);
        return new CellMetaInput(work.getCode(), roomId, ORIGIN_JOB, null, cmd.rationale(),
                override ? cmd.volumeReason() : null, cmd.status(), cmd.confidence(), file, page,
                priceStated ? cmd.priceReason() : null, override);
    }

    private static EstimateLineRoomQtyEntity findCell(EstimateEntity estimate, Long workItemId, Long roomId) {
        if (estimate == null || estimate.getLines() == null) {
            return null;
        }
        for (EstimateLineEntity line : estimate.getLines()) {
            if (line.getWorkItem() == null || !Objects.equals(line.getWorkItem().getId(), workItemId)
                    || line.getRoomQtys() == null) {
                continue;
            }
            for (EstimateLineRoomQtyEntity q : line.getRoomQtys()) {
                if (q.getRoom() != null && Objects.equals(q.getRoom().getId(), roomId)) {
                    return q;
                }
            }
        }
        return null;
    }
}
