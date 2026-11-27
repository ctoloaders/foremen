package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.foremen.dao.model.RoomEntity;

/**
 * FOR-05-04 (Requirement 5.1) — renders the saved rooms for the planner: id, label, type and the room
 * dimensions the estimate engine uses. Null dimensions are omitted. Never contains prices.
 */
public final class RoomContextBuilder {

    private RoomContextBuilder() {
    }

    public static String build(Collection<RoomEntity> rooms) {
        StringBuilder sb = new StringBuilder();
        for (RoomEntity r : rooms == null ? List.<RoomEntity>of() : rooms) {
            List<String> parts = new ArrayList<>();
            parts.add("id=" + r.getId());
            parts.add("label=" + (r.getLabel() == null ? "" : r.getLabel().strip()));
            if (r.getRoomType() != null) {
                parts.add("type=" + r.getRoomType().getCode());
            }
            add(parts, "floorArea", r.getFloorArea());
            add(parts, "wallArea", r.getWallArea());
            add(parts, "perimeter", r.getPerimeter());
            add(parts, "ceilingHeight", r.getCeilingHeight());
            add(parts, "internalCorners", r.getInternalCorners());
            add(parts, "doorCount", r.getDoorCount());
            add(parts, "doorHeight", r.getDoorHeight());
            add(parts, "doorWidth", r.getDoorWidth());
            add(parts, "doorArea", r.getDoorArea());
            add(parts, "windowCount", r.getWindowCount());
            add(parts, "windowHeight", r.getWindowHeight());
            add(parts, "windowWidth", r.getWindowWidth());
            add(parts, "windowArea", r.getWindowArea());
            add(parts, "wallGap", r.getWallGap());
            add(parts, "finishGap", r.getFinishGap());
            add(parts, "narrowPlace", r.getNarrowPlace());
            sb.append("- room ").append(String.join("; ", parts)).append('\n');
        }
        return sb.toString();
    }

    private static void add(List<String> parts, String name, Object v) {
        if (v == null) {
            return;
        }
        String s = v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
        parts.add(name + "=" + s);
    }
}
