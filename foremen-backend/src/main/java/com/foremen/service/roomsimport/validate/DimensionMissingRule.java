package com.foremen.service.roomsimport.validate;

import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.service.roomsimport.model.CalculatedMetrics;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code DIMENSION_MISSING} (Requirement 5.5): a dimension that could be neither read from the source
 * nor derived. The value stays {@code null} (never invented).
 *
 * <p>Covered fields: {@code floorArea, wallArea, perimeter, doorArea, windowArea, doorCount,
 * windowCount, doorHeight, doorWidth, windowHeight, windowWidth, internalCorners, ceilingHeight}.
 * {@code wallGap} / {@code finishGap} exist only as per-wall geometry values (a {@code RoomCreateRequest}
 * without geometry cannot carry them), so they are never reported. With geometry the five metrics, the
 * counts, the opening sizes and the reflex-vertex count are derivable, so only {@code ceilingHeight}
 * can be missing. Without geometry a door / window size is not reported when the matching count is
 * explicitly {@code 0}. Pure; never mutates its input.
 */
public final class DimensionMissingRule {

    private DimensionMissingRule() {}

    public static List<Warning> evaluate(String roomTempId, RoomCreateRequest room, CalculatedMetrics calculated) {
        List<Warning> out = new ArrayList<>();
        if (room == null) {
            return out;
        }
        boolean geometry = Num.hasUsablePolygon(room.geometry());
        if (room.ceilingHeight() == null) {
            out.add(missing(roomTempId, "ceilingHeight"));
        }
        if (geometry) {
            return out;
        }
        CalculatedMetrics c = calculated;
        check(out, roomTempId, "floorArea", room.floorArea() == null && (c == null || c.floorArea() == null));
        check(out, roomTempId, "wallArea", room.wallArea() == null && (c == null || c.wallArea() == null));
        check(out, roomTempId, "perimeter", room.perimeter() == null && (c == null || c.perimeter() == null));
        check(out, roomTempId, "doorArea", room.doorArea() == null && (c == null || c.doorArea() == null));
        check(out, roomTempId, "windowArea", room.windowArea() == null && (c == null || c.windowArea() == null));
        check(out, roomTempId, "doorCount", room.doorCount() == null);
        check(out, roomTempId, "windowCount", room.windowCount() == null);
        boolean noDoors = room.doorCount() != null && room.doorCount() == 0;
        boolean noWindows = room.windowCount() != null && room.windowCount() == 0;
        check(out, roomTempId, "doorHeight", room.doorHeight() == null && !noDoors);
        check(out, roomTempId, "doorWidth", room.doorWidth() == null && !noDoors);
        check(out, roomTempId, "windowHeight", room.windowHeight() == null && !noWindows);
        check(out, roomTempId, "windowWidth", room.windowWidth() == null && !noWindows);
        check(out, roomTempId, "internalCorners", room.internalCorners() == null);
        return out;
    }

    private static void check(List<Warning> out, String tempId, String field, boolean missing) {
        if (missing) {
            out.add(missing(tempId, field));
        }
    }

    private static Warning missing(String tempId, String field) {
        return new Warning(WarningCodes.DIMENSION_MISSING, Severity.INFO, tempId, field, Map.of("field", field), null);
    }
}
