package com.foremen.service.estimate.recalc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.estimate.EstimateAssignmentService;

/**
 * FOR-05-05 additional task 2 — an immutable value snapshot of an estimate's cells, taken before and
 * after a room save so {@link #diff} can report exactly what the automatic re-derivation changed.
 * Values are copied out of the entity graph at snapshot time (BigDecimal is immutable), so later
 * mutations of the same managed entities do not leak into an earlier snapshot.
 *
 * <p>Cells are keyed by {@code (workItemId, room identity)}; a not-yet-flushed new room has no id,
 * so its identity falls back to the room object identity.
 */
public final class EstimateSnapshot {

    private final Map<CellKey, Cell> cells;
    private final BigDecimal totalNet;

    private EstimateSnapshot(Map<CellKey, Cell> cells, BigDecimal totalNet) {
        this.cells = cells;
        this.totalNet = totalNet;
    }

    /** The estimate net total captured by the snapshot. */
    public BigDecimal totalNet() {
        return totalNet;
    }

    /** Snapshot of {@code estimate}'s current cells and net total. */
    public static EstimateSnapshot of(EstimateEntity estimate) {
        Map<CellKey, Cell> cells = new LinkedHashMap<>();
        for (EstimateLineEntity line : estimate.getLines()) {
            WorkItemEntity work = line.getWorkItem();
            if (work == null) {
                continue;
            }
            BigDecimal unitPrice = line.getUnitPrice() != null ? line.getUnitPrice() : BigDecimal.ZERO;
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                RoomEntity room = roomQty.getRoom();
                if (room == null) {
                    continue;
                }
                BigDecimal volume = roomQty.getQuantity() != null ? roomQty.getQuantity() : BigDecimal.ZERO;
                Map<Object, BigDecimal> materials = new HashMap<>();
                for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                    Object key = material.getId() != null ? material.getId() : System.identityHashCode(material);
                    materials.put(key, EstimateAssignmentService.materialNeed(material));
                }
                cells.put(new CellKey(work.getId(), roomIdentity(room)), new Cell(
                        work.getId(),
                        work.getNameRU(),
                        work.getNamePL(),
                        work.getUnit() != null ? work.getUnit().getCode() : null,
                        room.getId(),
                        roomName(room, true),
                        roomName(room, false),
                        volume,
                        round2(unitPrice.multiply(volume)),
                        materials));
            }
        }
        return new EstimateSnapshot(cells, estimate.getTotalNet() != null ? estimate.getTotalNet() : BigDecimal.ZERO);
    }

    /**
     * The impact of going from {@code before} to {@code after}: cells only in {@code after} are
     * ADDED, cells only in {@code before} are REMOVED, and cells in both whose Volume or any material
     * quantity differs are CHANGED. Ordered by room then work for a stable presentation.
     */
    public static EstimateImpact diff(EstimateSnapshot before, EstimateSnapshot after) {
        List<EstimateImpact.Change> changes = new ArrayList<>();
        Set<CellKey> keys = new HashSet<>(before.cells.keySet());
        keys.addAll(after.cells.keySet());
        for (CellKey key : keys) {
            Cell was = before.cells.get(key);
            Cell now = after.cells.get(key);
            if (was == null) {
                changes.add(change(EstimateImpact.Kind.ADDED, now, null, now, now.materials.size()));
            } else if (now == null) {
                changes.add(change(EstimateImpact.Kind.REMOVED, was, was, null, was.materials.size()));
            } else {
                int materialsChanged = materialsChanged(was.materials, now.materials);
                if (was.volume.compareTo(now.volume) != 0 || materialsChanged > 0) {
                    changes.add(change(EstimateImpact.Kind.CHANGED, now, was, now, materialsChanged));
                }
            }
        }
        changes.sort(Comparator
                .comparing((EstimateImpact.Change c) -> c.roomLabelPl() == null ? "" : c.roomLabelPl())
                .thenComparing(c -> c.roomId() == null ? Long.MAX_VALUE : c.roomId())
                .thenComparing(c -> c.workNamePl() == null ? "" : c.workNamePl())
                .thenComparing(EstimateImpact.Change::workItemId));
        return new EstimateImpact(List.copyOf(changes), before.totalNet, after.totalNet);
    }

    private static EstimateImpact.Change change(
            EstimateImpact.Kind kind, Cell identity, Cell was, Cell now, int materialsChanged) {
        return new EstimateImpact.Change(
                kind,
                identity.workItemId,
                identity.workNameRu,
                identity.workNamePl,
                identity.unitCode,
                identity.roomId,
                identity.roomNameRu,
                identity.roomNamePl,
                was != null ? was.volume : null,
                now != null ? now.volume : null,
                was != null ? was.labour : null,
                now != null ? now.labour : null,
                materialsChanged);
    }

    private static int materialsChanged(Map<Object, BigDecimal> was, Map<Object, BigDecimal> now) {
        int changed = 0;
        Set<Object> keys = new HashSet<>(was.keySet());
        keys.addAll(now.keySet());
        for (Object key : keys) {
            BigDecimal a = was.get(key);
            BigDecimal b = now.get(key);
            if (a == null || b == null || a.compareTo(b) != 0) {
                changed++;
            }
        }
        return changed;
    }

    private static Object roomIdentity(RoomEntity room) {
        return room.getId() != null ? room.getId() : (Object) ("new@" + System.identityHashCode(room));
    }

    private static String roomName(RoomEntity room, boolean ru) {
        if (room.getLabel() != null && !room.getLabel().isBlank()) {
            return room.getLabel();
        }
        RoomTypeEntity type = room.getRoomType();
        if (type == null) {
            return null;
        }
        return ru ? type.getNameRU() : type.getNamePL();
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record CellKey(Long workItemId, Object room) {
        CellKey {
            Objects.requireNonNull(room);
        }
    }

    private record Cell(
            Long workItemId,
            String workNameRu,
            String workNamePl,
            String unitCode,
            Long roomId,
            String roomNameRu,
            String roomNamePl,
            BigDecimal volume,
            BigDecimal labour,
            Map<Object, BigDecimal> materials) {
    }
}
