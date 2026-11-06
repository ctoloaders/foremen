package com.foremen.service.roomsimport.validate;

import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.CalculatedMetrics;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code OPENINGS_EXCEED_WALL} (door + window area above the gross wall area) and
 * {@code OPENING_TOO_WIDE} (an opening wider than its wall).
 */
public class OpeningsRule implements ValidationRule {

    @Override
    public List<Warning> apply(ValidationContext ctx) {
        List<Warning> out = new ArrayList<>();
        exceedWall(ctx, out);
        tooWide(ctx, out);
        return out;
    }

    private static void exceedWall(ValidationContext ctx, List<Warning> out) {
        CalculatedMetrics calc = ctx.calculated();
        double height = Num.dbl(ctx.ceilingHeight());
        if (calc == null || calc.perimeter() == null || !(height > 0)) {
            return;
        }
        double gross = calc.perimeter().doubleValue() * height;
        double openings = Num.dbl(calc.doorArea()) + Num.dbl(calc.windowArea());
        if (openings > gross + 1e-9) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("openingsArea", openings);
            params.put("grossWallArea", gross);
            out.add(new Warning(WarningCodes.OPENINGS_EXCEED_WALL, Severity.WARNING, ctx.tempId(),
                    "geometry", params, null));
        }
    }

    private static void tooWide(ValidationContext ctx, List<Warning> out) {
        RoomGeometry g = ctx.geometry();
        if (!Num.hasUsablePolygon(g) || g.getWalls() == null) {
            return;
        }
        int n = g.getVertices().size();
        for (int i = 0; i < g.getWalls().size() && i < n; i++) {
            RoomGeometry.Wall wall = g.getWalls().get(i);
            if (wall == null || wall.getOpenings() == null) {
                continue;
            }
            double length = Num.edgeLength(g.getVertices(), i);
            for (int j = 0; j < wall.getOpenings().size(); j++) {
                RoomGeometry.Opening o = wall.getOpenings().get(j);
                if (o != null && Double.isFinite(o.getWidth()) && o.getWidth() > length + 1e-9) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("wallIndex", i);
                    params.put("wallLength", length);
                    params.put("width", o.getWidth());
                    out.add(new Warning(WarningCodes.OPENING_TOO_WIDE, Severity.WARNING, ctx.tempId(),
                            "geometry.walls[" + i + "].openings[" + j + "]", params, null));
                }
            }
        }
    }
}
