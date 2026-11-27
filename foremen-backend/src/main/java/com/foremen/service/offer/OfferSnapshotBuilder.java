package com.foremen.service.offer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.BaseEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.estimate.CellAiMeta;
import com.foremen.service.estimate.CellFactsResolver;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.EstimatePackagePricingService;

/**
 * FOR-05-07 Batch 2 / FOR-05-07b: builds the {@link OfferSnapshot} from the live estimate. Static and
 * dependency-free so {@code OfferService} captures it in the same transaction as the offer without new
 * constructor dependencies. Rows follow the estimate's line order, grouped by work category.
 *
 * <p>Version 3 (FOR-05-04): each cell also carries the override flag, the engine facts and the staff-only
 * AI explanation; version 2 snapshots stay readable. Requirement 23.2: every built snapshot also stores
 * the cell's consumable-material lines, including manual quantity overrides and chosen products.
 *
 * <p>Version 2: lines applied to no room (no cell with a positive quantity) are skipped, groups and rooms
 * come only from surviving rows, and the snapshot carries the applied package marker and the per-line
 * restore data (override, comment, work price id, line number).
 */
public final class OfferSnapshotBuilder {

    public static final int VERSION = 3;

    private OfferSnapshotBuilder() {
    }

    /**
     * FOR-05-04 (Requirement 21.3): the version 3 cell. Carries the override flag (so a restore keeps a
     * manual or AI volume), the engine's formula key / fallback flag when the AI explanation recorded
     * them, and a copy of the cell's {@code aiMeta}. Absent values stay {@code null} (omitted in JSON).
     */
    static OfferSnapshot.Cell cell(Long roomId, EstimateLineRoomQtyEntity rq) {
        CellAiMeta ai = rq.getAiMeta();
        CellAiMeta.Volume volume = ai != null ? ai.volume() : null;
        String formulaKey = volume != null ? volume.formulaKey() : null;
        boolean fallback = volume != null && (CellFactsResolver.SOURCE_UNIT_FALLBACK.equals(volume.source())
                || CellFactsResolver.FALLBACK_KEY.equals(formulaKey));
        return new OfferSnapshot.Cell(roomId, rq.getQuantity(),
                rq.isVolumeOverridden() ? Boolean.TRUE : null, formulaKey,
                fallback ? Boolean.TRUE : null, ai, materialLines(rq));
    }

    /**
     * FOR-05-04 (Requirements 23.2, 23.3): the cell's consumable-material lines in id order. Always a list
     * (empty when the work has no norms), so a version 3 snapshot states "no materials" explicitly.
     */
    static List<OfferSnapshot.MaterialLine> materialLines(EstimateLineRoomQtyEntity rq) {
        List<EstimateLineRoomMaterialEntity> materials = rq.getMaterials();
        if (materials == null || materials.isEmpty()) {
            return List.of();
        }
        List<EstimateLineRoomMaterialEntity> sorted = new ArrayList<>(materials);
        sorted.sort(Comparator.comparing(EstimateLineRoomMaterialEntity::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<OfferSnapshot.MaterialLine> out = new ArrayList<>(sorted.size());
        for (EstimateLineRoomMaterialEntity m : sorted) {
            out.add(materialLine(m));
        }
        return out;
    }

    private static OfferSnapshot.MaterialLine materialLine(EstimateLineRoomMaterialEntity m) {
        BigDecimal quantity = EstimateAssignmentService.materialNeed(m).setScale(4, RoundingMode.HALF_UP);
        return new OfferSnapshot.MaterialLine(
                m.getBranch() != null ? m.getBranch().name() : null,
                idOf(m.getConstructionType()),
                idOf(m.getFinishingType()),
                m.getNormQty(),
                m.getConsumptionBasis() != null ? m.getConsumptionBasis().name() : null,
                m.getRangeMin(),
                m.getRangeMax(),
                m.isQtyOverridden() ? Boolean.TRUE : null,
                m.isQtyOverridden() ? m.getManualQty() : null,
                quantity,
                idOf(m.getConcreteConstructionMaterial()),
                idOf(m.getConcreteFinishingMaterial()),
                m.getConcreteNet(),
                m.isAppliedFromPackage() ? Boolean.TRUE : null,
                idOf(m.getSourceConstructionMaterial()),
                idOf(m.getSourceFinishingMaterial()));
    }

    private static Long idOf(BaseEntity entity) {
        return entity != null ? entity.getId() : null;
    }

    /** Builds a snapshot with the package code taken from the estimate and no package names. */
    public static OfferSnapshot build(EstimateEntity estimate, BigDecimal vatRatePct,
                                      BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross) {
        return build(estimate, vatRatePct, totalNet, totalVat, totalGross,
                estimate != null ? estimate.getAppliedPackageCode() : null, null, null);
    }

    /**
     * @param packageCode   applied package code (null when none; then the names are ignored)
     * @param packageNameRu localized package name resolved by the caller, nullable
     * @param packageNamePl localized package name resolved by the caller, nullable
     */
    public static OfferSnapshot build(EstimateEntity estimate, BigDecimal vatRatePct,
                                      BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross,
                                      String packageCode, String packageNameRu, String packageNamePl) {
        return build(estimate, vatRatePct, totalNet, totalVat, totalGross,
                packageCode, packageNameRu, packageNamePl, null);
    }

    /**
     * D7: as above, plus the client prices the offer totals were computed over (the map of
     * {@code OfferService.estimateClientPrices}). When it carries the synthetic package key, the snapshot
     * records the package subtotal and marks every row whose line is covered by the package (absent from
     * the map); {@code null} means no package pricing.
     */
    public static OfferSnapshot build(EstimateEntity estimate, BigDecimal vatRatePct,
                                      BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross,
                                      String packageCode, String packageNameRu, String packageNamePl,
                                      Map<Long, BigDecimal> clientPrices) {
        BigDecimal packageSubtotal = clientPrices != null
                ? clientPrices.get(EstimatePackagePricingService.PACKAGE_PRICE_KEY) : null;
        Map<Long, OfferSnapshot.Room> rooms = new LinkedHashMap<>();
        Map<Long, List<OfferSnapshot.Row>> rowsByCategory = new LinkedHashMap<>();
        Map<Long, WorkCategoryEntity> categories = new LinkedHashMap<>();
        List<EstimateLineEntity> lines = estimate.getLines() != null ? estimate.getLines() : List.of();
        for (EstimateLineEntity line : lines) {
            if (line == null || line.getWorkItem() == null) {
                continue;
            }
            List<OfferSnapshot.Cell> cells = new ArrayList<>();
            Map<Long, OfferSnapshot.Room> lineRooms = new LinkedHashMap<>();
            if (line.getRoomQtys() != null) {
                for (EstimateLineRoomQtyEntity rq : line.getRoomQtys()) {
                    RoomEntity room = rq.getRoom();
                    if (room == null || rq.getQuantity() == null || rq.getQuantity().signum() <= 0) {
                        continue;
                    }
                    lineRooms.putIfAbsent(room.getId(), new OfferSnapshot.Room(room.getId(), room.getLabel()));
                    cells.add(cell(room.getId(), rq));
                }
            }
            if (cells.isEmpty()) {
                continue; // applied to no room: not part of the offer picture
            }
            lineRooms.forEach(rooms::putIfAbsent);
            WorkItemEntity work = line.getWorkItem();
            WorkCategoryEntity category = work.getWorkCategory();
            Long categoryId = category != null ? category.getId() : null;
            categories.putIfAbsent(categoryId, category);
            BigDecimal effectivePrice = line.getUnitPriceOverride() != null
                    ? line.getUnitPriceOverride() : line.getUnitPrice();
            MeasurementUnitEntity unit = line.getUnit() != null ? line.getUnit() : work.getUnit();
            rowsByCategory.computeIfAbsent(categoryId, k -> new ArrayList<>()).add(new OfferSnapshot.Row(
                    work.getId(), work.getNameRU(), work.getNamePL(),
                    unit != null ? unit.getCode() : null,
                    unit != null ? unit.getNameRU() : null,
                    unit != null ? unit.getNamePL() : null,
                    line.getQuantity(), effectivePrice, line.getUnitPrice(),
                    line.effectiveValueNet(), line.originalValueNet(), cells,
                    line.getUnitPriceOverride(), line.getComment(),
                    line.getWorkPrice() != null ? line.getWorkPrice().getId() : null,
                    line.getLineNo(), line.getId(),
                    packageSubtotal != null ? !clientPrices.containsKey(line.getId()) : null));
        }
        List<OfferSnapshot.Group> groups = new ArrayList<>();
        rowsByCategory.forEach((categoryId, rows) -> {
            WorkCategoryEntity c = categories.get(categoryId);
            groups.add(new OfferSnapshot.Group(
                    categoryId, c != null ? c.getNameRU() : null, c != null ? c.getNamePL() : null, rows));
        });
        boolean hasPackage = packageCode != null && !packageCode.isBlank();
        return new OfferSnapshot(VERSION, OffsetDateTime.now().toString(), vatRatePct,
                totalNet, totalVat, totalGross, new ArrayList<>(rooms.values()), groups,
                hasPackage ? packageCode : null,
                hasPackage ? packageNameRu : null,
                hasPackage ? packageNamePl : null,
                packageSubtotal);
    }
}
