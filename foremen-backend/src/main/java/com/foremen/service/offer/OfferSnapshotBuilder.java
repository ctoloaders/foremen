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
 * FOR-05-07 Batch 2: builds the {@link OfferSnapshot} from the live estimate. Static and dependency-free
 * so {@code OfferService.prepareOffer} captures it in the same transaction as the offer without new
 * constructor dependencies. Rows follow the estimate's line order, grouped by work category.
 */
public final class OfferSnapshotBuilder {

    public static final int VERSION = 1;

    private OfferSnapshotBuilder() {
    }

    public static OfferSnapshot build(EstimateEntity estimate, BigDecimal vatRatePct,
                                      BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalGross) {
        Map<Long, OfferSnapshot.Room> rooms = new LinkedHashMap<>();
        Map<Long, List<OfferSnapshot.Row>> rowsByCategory = new LinkedHashMap<>();
        Map<Long, WorkCategoryEntity> categories = new LinkedHashMap<>();
        List<EstimateLineEntity> lines = estimate.getLines() != null ? estimate.getLines() : List.of();
        for (EstimateLineEntity line : lines) {
            if (line == null || line.getWorkItem() == null) {
                continue;
            }
            WorkItemEntity work = line.getWorkItem();
            WorkCategoryEntity category = work.getWorkCategory();
            Long categoryId = category != null ? category.getId() : null;
            categories.putIfAbsent(categoryId, category);
            List<OfferSnapshot.Cell> cells = new ArrayList<>();
            if (line.getRoomQtys() != null) {
                for (EstimateLineRoomQtyEntity rq : line.getRoomQtys()) {
                    RoomEntity room = rq.getRoom();
                    if (room == null || rq.getQuantity() == null) {
                        continue;
                    }
                    rooms.putIfAbsent(room.getId(), new OfferSnapshot.Room(room.getId(), room.getLabel()));
                    cells.add(new OfferSnapshot.Cell(room.getId(), rq.getQuantity()));
                }
            }
            BigDecimal effectivePrice = line.getUnitPriceOverride() != null
                    ? line.getUnitPriceOverride() : line.getUnitPrice();
            MeasurementUnitEntity unit = line.getUnit() != null ? line.getUnit() : work.getUnit();
            rowsByCategory.computeIfAbsent(categoryId, k -> new ArrayList<>()).add(new OfferSnapshot.Row(
                    work.getId(), work.getNameRU(), work.getNamePL(),
                    unit != null ? unit.getCode() : null,
                    unit != null ? unit.getNameRU() : null,
                    unit != null ? unit.getNamePL() : null,
                    line.getQuantity(), effectivePrice, line.getUnitPrice(),
                    line.effectiveValueNet(), line.originalValueNet(), cells));
        }
        List<OfferSnapshot.Group> groups = new ArrayList<>();
        rowsByCategory.forEach((categoryId, rows) -> {
            WorkCategoryEntity c = categories.get(categoryId);
            groups.add(new OfferSnapshot.Group(
                    categoryId, c != null ? c.getNameRU() : null, c != null ? c.getNamePL() : null, rows));
        });
        return new OfferSnapshot(VERSION, OffsetDateTime.now().toString(), vatRatePct,
                totalNet, totalVat, totalGross, new ArrayList<>(rooms.values()), groups);
    }
}
