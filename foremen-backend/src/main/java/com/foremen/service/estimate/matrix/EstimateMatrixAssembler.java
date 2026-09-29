package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.service.formula.VolumeResolver;

/**
 * Builds the Estimate tab read model ({@link EstimateMatrixDto}) from the estimate entity graph
 * (FOR-05-05, design §B6). The one server-side computation surface for the matrix: it lays out the
 * works&times;rooms grid, computes each cell's single Volume + labour + material cost range + fill
 * state, and folds those into the group subtotals, header totals, and the materials fill indicator —
 * all using the estimate's frozen copied prices and the pure {@link VolumeResolver}.
 *
 * <p><b>Cost model (mirrors the frontend {@code costModel.ts}, R4/R6/R14).</b> A cell's single Volume
 * {@code V} is the stored {@code EstimateLineRoomQty.quantity}; labour is the point {@code unitPrice ×
 * V}; each material line contributes {@code norm × V × [rangeMin..rangeMax]} as a Placeholder or the
 * point {@code norm × V × concreteNet} when a concrete product is chosen (R4.3, R6.4). The cell cost
 * range is {@code labour + Σ material contributions}; it collapses ({@code min == max}) iff every
 * material line is concrete (R7.2). Group / header subtotals sum the per-branch contributions across
 * cells and collapse per branch on the same rule (R2.2, R14.1, R14.2). The fill indicator is the
 * concrete-material-line share of all assigned material lines, in percent (R14.3).
 *
 * <p><b>Volume provenance for the report (R4.4, R5.3).</b> The stored quantity does not record how it
 * was resolved, so {@code formulaUsed}/{@code fallbackUsed} are re-derived through the pure
 * {@link VolumeResolver} against the work's default formula and the cell's room — the matrix read has
 * no active-package context, matching {@code assign}'s package-less default. {@code formulaUsed} is
 * the applicable formula's source text, or {@code null} when the unit fallback supplied the Volume.
 *
 * <p>Stateless and read-only: it reads the already-loaded/traversable graph plus the catalog rows
 * (all work items, the project's rooms, the works' default formulas) and returns a fresh DTO. Names
 * are localized (RU/PL) from the request locale, mirroring the repo's read-path convention.
 */
@Component
public class EstimateMatrixAssembler {

    private final RoomDao roomDao;
    private final WorkItemDao workItemDao;
    private final WorkVolumeFormulaDao workVolumeFormulaDao;
    private final WorkMaterialConsumptionDao workMaterialConsumptionDao;

    public EstimateMatrixAssembler(
            RoomDao roomDao,
            WorkItemDao workItemDao,
            WorkVolumeFormulaDao workVolumeFormulaDao,
            WorkMaterialConsumptionDao workMaterialConsumptionDao) {
        this.roomDao = roomDao;
        this.workItemDao = workItemDao;
        this.workVolumeFormulaDao = workVolumeFormulaDao;
        this.workMaterialConsumptionDao = workMaterialConsumptionDao;
    }

    /**
     * Assembles the full matrix read model for {@code estimate}'s project.
     *
     * @param estimate the project's estimate (its lines/room-qtys/materials are traversed)
     * @param projectId the owning project id (the room-column set)
     * @param editable whether the estimate may be written (DRAFT + caller has ESTIMATE UPDATE, R15.5)
     * @return the assembled {@link EstimateMatrixDto}
     */
    public EstimateMatrixDto assemble(EstimateEntity estimate, Long projectId, boolean editable) {
        boolean ru = isRussianLocale();

        List<RoomEntity> rooms = roomDao.findByProjectId(projectId);
        List<EstimateMatrixRoomDto> roomDtos = new ArrayList<>(rooms.size());
        for (RoomEntity room : rooms) {
            roomDtos.add(new EstimateMatrixRoomDto(
                    room.getId(), room.getLabel(), roomTypeId(room), roomTypeName(room, ru)));
        }

        // Index the estimate's assigned cells by (workItemId, roomId) for O(1) lookup per cell.
        Map<CellKey, EstimateLineRoomQtyEntity> assigned = indexAssignedCells(estimate);
        Map<Long, EstimateLineEntity> lineByWork = indexLinesByWork(estimate);

        Map<Long, WorkVolumeFormulaEntity> formulasByWork = indexFormulasByWork();

        // Partition work rows into groups by work category, preserving a deterministic order (R2.1).
        Map<Long, GroupAccumulator> groups = new LinkedHashMap<>();
        MaterialFold headerFold = new MaterialFold();
        FillCounter fillCounter = new FillCounter();
        // Header package summary fold (FOR-05-05 Amendment A1): the estimate-wide package-allocated
        // finishing volume total and the package-flagged lines' money contribution (point F).
        PackageFold headerPackageFold = new PackageFold();

        List<WorkItemEntity> sortedWorks = new ArrayList<>();
        workItemDao.findAll().forEach(sortedWorks::add);
        sortedWorks.sort(Comparator
                .comparing(EstimateMatrixAssembler::categoryOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WorkItemEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())));

        // Batch-load every work's declared consumptions ONCE (perf: was one DAO call per material
        // line via resolveNormUnit — a severe N+1 on large projects). The DAO's @EntityGraph eagerly
        // loads getWorkItem(), so grouping by workItem id is safe.
        List<Long> workItemIds = sortedWorks.stream()
                .map(WorkItemEntity::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWorkId =
                (workItemIds.isEmpty() ? List.<WorkMaterialConsumptionEntity>of()
                        : workMaterialConsumptionDao.findByWorkItemIdIn(workItemIds))
                        .stream()
                        .filter(c -> c.getWorkItem() != null && c.getWorkItem().getId() != null)
                        .collect(java.util.stream.Collectors.groupingBy(c -> c.getWorkItem().getId()));

        for (WorkItemEntity work : sortedWorks) {
            WorkCategoryEntity category = work.getWorkCategory();
            Long categoryId = category != null ? category.getId() : null;
            GroupAccumulator group = groups.computeIfAbsent(
                    categoryId, id -> new GroupAccumulator(categoryId, workCategoryName(category, ru)));

            WorkVolumeFormulaEntity defaultFormula = formulasByWork.get(work.getId());
            List<CellDto> cells = new ArrayList<>(rooms.size());
            // Per-row package summary fold (FOR-05-05 Amendment A1): the row's package-allocated
            // finishing volume across all its cells (the value of the trailing package column).
            PackageFold rowPackageFold = new PackageFold();
            for (RoomEntity room : rooms) {
                EstimateLineRoomQtyEntity roomQty = assigned.get(new CellKey(work.getId(), room.getId()));
                if (roomQty == null) {
                    cells.add(CellDto.unassigned(work.getId(), room.getId()));
                    continue;
                }
                EstimateLineEntity line = lineByWork.get(work.getId());
                CellDto cell = assembleCell(work, room, roomQty, line, defaultFormula, ru, consumptionsByWorkId);
                cells.add(cell);
                group.fold.add(cell);
                headerFold.add(cell);
                fillCounter.count(cell);
                rowPackageFold.add(cell);
            }

            group.packageFold.merge(rowPackageFold);
            headerPackageFold.merge(rowPackageFold);
            group.rows.add(new WorkRowDto(
                    work.getId(), workItemName(work, ru), roomTypeIds(work), cells,
                    rowPackageFold.volume(), rowPackageFold.toMoney()));
        }

        List<WorkTypeGroupDto> groupDtos = new ArrayList<>(groups.size());
        for (GroupAccumulator group : groups.values()) {
            groupDtos.add(new WorkTypeGroupDto(
                    group.categoryId, group.categoryName, group.rows, group.fold.toSubtotals(),
                    group.packageFold.volume(), group.packageFold.toMoney()));
        }

        return new EstimateMatrixDto(
                projectId,
                editable,
                roomDtos,
                groupDtos,
                headerFold.toSubtotals(),
                fillCounter.toPct(),
                headerPackageFold.volume(),
                headerPackageFold.toMoney(),
                estimate.getAppliedPackageCode());
    }

    // --- per-cell assembly ------------------------------------------------------------------

    /** Builds an assigned cell: single Volume, labour point, material lines, cost range, fill state. */
    private CellDto assembleCell(
            WorkItemEntity work,
            RoomEntity room,
            EstimateLineRoomQtyEntity roomQty,
            EstimateLineEntity line,
            WorkVolumeFormulaEntity defaultFormula,
            boolean ru,
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWorkId) {
        BigDecimal volume = roomQty.getQuantity() != null ? roomQty.getQuantity() : BigDecimal.ZERO;

        // Volume provenance for the Cell_Report (R4.4, R5.3): re-derived, package-less, via the pure
        // resolver — the matrix read has no active-package context (matches assign's default).
        String unitCode = work.getUnit() != null ? work.getUnit().getCode() : null;
        VolumeResolver.Resolution resolution =
                VolumeResolver.resolve(null, defaultFormula, unitCode, room);
        boolean fallbackUsed = resolution.fallbackUsed();
        String formulaUsed = fallbackUsed || defaultFormula == null ? null : defaultFormula.getSourceText();
        // A stable formula key the frontend maps to a localized label (#2): "fallback" when the
        // unit→dimension fallback supplied the Volume, else the default formula's raw source text
        // (e.g. "floorArea"), else null. formulaUsed stays the raw fallback text.
        String formulaKey = fallbackUsed ? "fallback"
                : (defaultFormula != null ? defaultFormula.getSourceText() : null);

        BigDecimal unitPrice = line != null && line.getUnitPrice() != null
                ? line.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal labour = unitPrice.multiply(volume);

        List<MaterialLineDto> materialDtos = new ArrayList<>();
        MoneyRange materialsRange = MoneyRange.ZERO;
        int concreteCount = 0;
        int total = 0;
        for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
            MaterialLineDto dto = materialLineDto(work, material, volume, ru, consumptionsByWorkId);
            materialDtos.add(dto);
            materialsRange = add(materialsRange, materialContribution(dto));
            total++;
            if (dto.isConcrete()) {
                concreteCount++;
            }
        }

        MoneyRange costRange = add(MoneyRange.point(labour), materialsRange);
        FillState fillState = fillState(total, concreteCount);

        return new CellDto(
                work.getId(),
                room.getId(),
                true,
                volume,
                formulaUsed,
                formulaKey,
                fallbackUsed,
                roomQty.isVolumeOverridden(),
                labour,
                materialDtos,
                costRange,
                fillState);
    }

    /**
     * Maps a stored material line to its read DTO, localizing the type + concrete-product names and
     * resolving the norm's unit code (#8). The estimate line does not persist a unit and the material
     * TYPE entities carry none, so the norm unit is resolved at READ time from the work's declared
     * {@code WorkMaterialConsumption} for the line's {@code (branch, type)} — its {@code materialUnit}
     * code — and is {@code null} when no matching consumption exists.
     */
    private MaterialLineDto materialLineDto(
            WorkItemEntity work, EstimateLineRoomMaterialEntity material, BigDecimal volume, boolean ru,
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWorkId) {
        ConsumptionBranch branch = material.getBranch();
        Long typeId;
        String typeName;
        Long concreteId = null;
        String concreteName = null;

        if (branch == ConsumptionBranch.construction) {
            ConstructionMaterialTypeEntity type = material.getConstructionType();
            typeId = type != null ? type.getId() : null;
            typeName = type != null ? localizedName(ru, type.getNameRU(), type.getNamePL()) : null;
            ConstructionMaterialEntity concrete = material.getConcreteConstructionMaterial();
            if (concrete != null) {
                concreteId = concrete.getId();
                concreteName = localizedName(ru, concrete.getNameRU(), concrete.getNamePL());
            }
        } else {
            MaterialTypeEntity type = material.getFinishingType();
            typeId = type != null ? type.getId() : null;
            typeName = type != null ? localizedName(ru, type.getNameRU(), type.getNamePL()) : null;
            FinishingMaterialEntity concrete = material.getConcreteFinishingMaterial();
            if (concrete != null) {
                concreteId = concrete.getId();
                concreteName = finishingMaterialName(concrete, ru);
            }
        }

        Long workId = work != null ? work.getId() : null;
        String normUnit = resolveNormUnit(workId, branch, typeId, consumptionsByWorkId);

        ConsumptionBasis basis = material.getConsumptionBasis() != null
                ? material.getConsumptionBasis() : ConsumptionBasis.PER_UNIT;
        BigDecimal resolvedQty = resolvedPhysicalQuantity(material, volume);

        return new MaterialLineDto(
                material.getId(),
                branch,
                typeId,
                typeName,
                material.getNormQty(),
                material.getRangeMin(),
                material.getRangeMax(),
                concreteId,
                concreteName,
                material.getConcreteNet(),
                normUnit,
                resolvedQty,
                material.isQtyOverridden(),
                basis,
                material.isAppliedFromPackage());
    }

    /**
     * The single "resolved physical quantity for a material line" helper (FOR-05-05 amendments #1/#4)
     * — the ONE place the override + basis rules live, shared by the per-cell money contribution and
     * the group/header {@link MaterialFold} so the displayed quantity and the money never drift:
     * <ol>
     *   <li>if the line is manually overridden (#1) → {@code manualQty} (override wins over basis);</li>
     *   <li>else if the copied basis is {@code PER_ROOM} (#4) → {@code norm} (i.e. {@code norm × 1},
     *       independent of Volume);</li>
     *   <li>else ({@code PER_UNIT}) → {@code norm × Volume} as before.</li>
     * </ol>
     * A {@code null} norm / manualQty contributes zero (no fabricated value).
     */
    private static BigDecimal resolvedPhysicalQuantity(EstimateLineRoomMaterialEntity material, BigDecimal volume) {
        if (material.isQtyOverridden()) {
            return nz(material.getManualQty());
        }
        BigDecimal norm = nz(material.getNormQty());
        if (material.getConsumptionBasis() == ConsumptionBasis.PER_ROOM) {
            return norm; // norm × 1 — a fixed quantity for the whole room (#4)
        }
        return norm.multiply(nz(volume)); // PER_UNIT: norm × Volume (default)
    }

    /**
     * Resolves the norm's unit code for {@code (branch, typeId)} from the work's declared consumptions
     * (#8): finds the {@code WorkMaterialConsumption} whose branch + material-type match the line and
     * returns its {@code materialUnit} code. {@code null} when the work is missing, the type is unset,
     * or the work declares no consumption for that type (e.g. an ad-hoc material line).
     */
    private String resolveNormUnit(
            Long workId, ConsumptionBranch branch, Long typeId,
            Map<Long, List<WorkMaterialConsumptionEntity>> consumptionsByWorkId) {
        if (workId == null || branch == null || typeId == null) {
            return null;
        }
        List<WorkMaterialConsumptionEntity> consumptions =
                consumptionsByWorkId.getOrDefault(workId, List.of());
        for (WorkMaterialConsumptionEntity consumption : consumptions) {
            if (consumption.getBranch() != branch) {
                continue;
            }
            Long consumptionTypeId = branch == ConsumptionBranch.construction
                    ? (consumption.getConstructionMaterialType() != null
                            ? consumption.getConstructionMaterialType().getId() : null)
                    : (consumption.getFinishingMaterialType() != null
                            ? consumption.getFinishingMaterialType().getId() : null);
            if (typeId.equals(consumptionTypeId)) {
                return consumption.getMaterialUnit() != null ? consumption.getMaterialUnit().getCode() : null;
            }
        }
        return null;
    }

    /**
     * The money contribution of one material line, using its RESOLVED physical quantity {@code Q}
     * (override- and basis-aware, FOR-05-05 amendments #1/#4 — {@link MaterialLineDto#quantity()}):
     * the point {@code Q × concreteNet} when concrete (R6.4), else the band
     * {@code Q × [rangeMin..rangeMax]} (R4.3). A {@code null} range edge contributes zero on that edge
     * (no fabricated value). The physical quantity is centralized in
     * {@link #resolvedPhysicalQuantity(EstimateLineRoomMaterialEntity, BigDecimal)} so the per-cell
     * contribution and the {@link MaterialFold} never diverge.
     */
    private static MoneyRange materialContribution(MaterialLineDto line) {
        BigDecimal quantity = nz(line.quantity());
        if (line.isConcrete()) {
            BigDecimal point = quantity.multiply(nz(line.concreteNet()));
            return MoneyRange.point(point);
        }
        BigDecimal min = quantity.multiply(nz(line.rangeMin()));
        BigDecimal max = quantity.multiply(nz(line.rangeMax()));
        return new MoneyRange(min, max);
    }

    // --- folding helpers --------------------------------------------------------------------

    /** Accumulates the per-branch subtotals of a set of cells (works point + construction/finishing bands). */
    private static final class MaterialFold {
        private BigDecimal works = BigDecimal.ZERO;
        private BigDecimal constructionMin = BigDecimal.ZERO;
        private BigDecimal constructionMax = BigDecimal.ZERO;
        private BigDecimal finishingMin = BigDecimal.ZERO;
        private BigDecimal finishingMax = BigDecimal.ZERO;

        void add(CellDto cell) {
            works = works.add(nz(cell.labour()));
            for (MaterialLineDto line : cell.materials()) {
                // The DTO already carries the resolved (override/basis-aware) physical quantity, so
                // the fold reuses the SAME contribution as the per-cell computation (no drift).
                MoneyRange contribution = materialContribution(line);
                if (line.branch() == ConsumptionBranch.construction) {
                    constructionMin = constructionMin.add(nz(contribution.min()));
                    constructionMax = constructionMax.add(nz(contribution.max()));
                } else if (line.branch() == ConsumptionBranch.finishing) {
                    finishingMin = finishingMin.add(nz(contribution.min()));
                    finishingMax = finishingMax.add(nz(contribution.max()));
                }
            }
        }

        BranchSubtotals toSubtotals() {
            return new BranchSubtotals(
                    MoneyRange.point(works),
                    new MoneyRange(constructionMin, constructionMax),
                    new MoneyRange(finishingMin, finishingMax));
        }
    }

    /**
     * Accumulates the package summary (FOR-05-05 Amendment A1) over a set of cells: the total
     * resolved quantity of the cells' {@code appliedFromPackage} material lines (the package-column
     * value / group subtotal / header total) and their money contribution (point F). The money reuses
     * the SAME per-line {@link #materialContribution(MaterialLineDto)} as the cost folds so a
     * package-flagged line's money never diverges between the cost total and the package total.
     */
    private static final class PackageFold {
        private BigDecimal volume = BigDecimal.ZERO;
        private BigDecimal moneyMin = BigDecimal.ZERO;
        private BigDecimal moneyMax = BigDecimal.ZERO;

        void add(CellDto cell) {
            for (MaterialLineDto line : cell.materials()) {
                if (!line.appliedFromPackage()) {
                    continue;
                }
                volume = volume.add(nz(line.quantity()));
                MoneyRange contribution = materialContribution(line);
                moneyMin = moneyMin.add(nz(contribution.min()));
                moneyMax = moneyMax.add(nz(contribution.max()));
            }
        }

        /** Rolls another fold (e.g. a row's) into this one (e.g. the header's). */
        void merge(PackageFold other) {
            volume = volume.add(other.volume);
            moneyMin = moneyMin.add(other.moneyMin);
            moneyMax = moneyMax.add(other.moneyMax);
        }

        BigDecimal volume() {
            return volume;
        }

        MoneyRange toMoney() {
            return new MoneyRange(moneyMin, moneyMax);
        }
    }

    /** Counts concrete vs total assigned material lines for the header fill indicator (R14.3). */
    private static final class FillCounter {
        private int concrete = 0;
        private int total = 0;

        void count(CellDto cell) {
            for (MaterialLineDto line : cell.materials()) {
                total++;
                if (line.isConcrete()) {
                    concrete++;
                }
            }
        }

        BigDecimal toPct() {
            if (total == 0) {
                return BigDecimal.ZERO; // no assigned material lines -> 0% (R14.3)
            }
            return BigDecimal.valueOf(concrete)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);
        }
    }

    /** Mutable per-group accumulator: its rows and its cell fold. */
    private static final class GroupAccumulator {
        private final Long categoryId;
        private final String categoryName;
        private final List<WorkRowDto> rows = new ArrayList<>();
        private final MaterialFold fold = new MaterialFold();
        /**
         * The group's package summary fold (FOR-05-05 Amendment A1 / FOR-05-04 Change #4): Σ of its
         * rows' package-allocated finishing volume AND their money contribution (rolled from each
         * row's fold via {@link PackageFold#merge}).
         */
        private final PackageFold packageFold = new PackageFold();

        GroupAccumulator(Long categoryId, String categoryName) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
        }
    }

    /** Classifies a cell's fill state from its concrete/total material-line counts (R8.1). */
    private static FillState fillState(int total, int concrete) {
        if (total > 0 && concrete == total) {
            return FillState.filled;
        }
        if (concrete == 0) {
            return FillState.placeholder;
        }
        return FillState.partial;
    }

    /** Sums two money ranges edge-wise. */
    private static MoneyRange add(MoneyRange a, MoneyRange b) {
        return new MoneyRange(nz(a.min()).add(nz(b.min())), nz(a.max()).add(nz(b.max())));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // --- indexing helpers -------------------------------------------------------------------

    private Map<CellKey, EstimateLineRoomQtyEntity> indexAssignedCells(EstimateEntity estimate) {
        Map<CellKey, EstimateLineRoomQtyEntity> index = new LinkedHashMap<>();
        for (EstimateLineEntity line : estimate.getLines()) {
            Long workItemId = line.getWorkItem() != null ? line.getWorkItem().getId() : null;
            if (workItemId == null) {
                continue;
            }
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                if (roomQty.getRoom() != null) {
                    index.put(new CellKey(workItemId, roomQty.getRoom().getId()), roomQty);
                }
            }
        }
        return index;
    }

    private Map<Long, EstimateLineEntity> indexLinesByWork(EstimateEntity estimate) {
        Map<Long, EstimateLineEntity> index = new LinkedHashMap<>();
        for (EstimateLineEntity line : estimate.getLines()) {
            if (line.getWorkItem() != null) {
                index.putIfAbsent(line.getWorkItem().getId(), line);
            }
        }
        return index;
    }

    private Map<Long, WorkVolumeFormulaEntity> indexFormulasByWork() {
        Map<Long, WorkVolumeFormulaEntity> index = new LinkedHashMap<>();
        for (WorkVolumeFormulaEntity formula : workVolumeFormulaDao.findAllWithWorkItem()) {
            if (formula.getWorkItem() != null) {
                index.putIfAbsent(formula.getWorkItem().getId(), formula);
            }
        }
        return index;
    }

    private static List<Long> roomTypeIds(WorkItemEntity work) {
        java.util.Set<RoomTypeEntity> roomTypes = work.getRoomTypes();
        if (roomTypes == null || roomTypes.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(roomTypes.size());
        for (RoomTypeEntity type : roomTypes) {
            if (type != null && type.getId() != null) {
                ids.add(type.getId());
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private static Integer categoryOrder(WorkItemEntity work) {
        WorkCategoryEntity category = work.getWorkCategory();
        return category != null ? category.getOrderNo() : null;
    }

    // --- localization -----------------------------------------------------------------------

    /** The room's type id (FOR-05-05 amendment #3), or {@code null} when the room has no type. */
    private static Long roomTypeId(RoomEntity room) {
        RoomTypeEntity type = room.getRoomType();
        return type != null ? type.getId() : null;
    }

    private static String roomTypeName(RoomEntity room, boolean ru) {
        RoomTypeEntity type = room.getRoomType();
        return type != null ? localizedName(ru, type.getNameRU(), type.getNamePL()) : null;
    }

    private static String workCategoryName(WorkCategoryEntity category, boolean ru) {
        return category != null ? localizedName(ru, category.getNameRU(), category.getNamePL()) : null;
    }

    private static String workItemName(WorkItemEntity work, boolean ru) {
        return localizedName(ru, work.getNameRU(), work.getNamePL());
    }

    private static String finishingMaterialName(FinishingMaterialEntity material, boolean ru) {
        if (material.getMaterial() == null) {
            return null;
        }
        return localizedName(ru, material.getMaterial().getNameRU(), material.getMaterial().getNamePL());
    }

    private static String localizedName(boolean ru, String nameRU, String namePL) {
        return ru ? nameRU : namePL;
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /** A {@code (workItemId, roomId)} cell identity for indexing assigned cells. */
    private record CellKey(Long workItemId, Long roomId) {
    }
}
