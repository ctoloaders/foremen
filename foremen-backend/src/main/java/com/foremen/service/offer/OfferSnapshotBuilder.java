package com.foremen.service.offer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;

/**
 * FOR-05-07 Batch 2 / FOR-05-07b: builds the {@link OfferSnapshot} from the live estimate. Static and
 * dependency-free so {@code OfferService} captures it in the same transaction as the offer without new
 * constructor dependencies. Rows follow the estimate's line order, grouped by work category.
 *
 * <p>Version 2: lines applied to no room (no cell with a positive quantity) are skipped, groups and rooms
 * come only from surviving rows, and the snapshot carries the applied package marker and the per-line
 * restore data (override, comment, work price id, line number).
 */
public final class OfferSnapshotBuilder {

    public static final int VERSION = 2;

    private OfferSnapshotBuilder() {
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
                    cells.add(new OfferSnapshot.Cell(room.getId(), rq.getQuantity()));
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
                    line.getLineNo()));
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
                hasPackage ? packageNamePl : null);
    }
}
