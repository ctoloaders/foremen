package com.foremen.service.roomsimport.validate;

import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code INTERNAL_CORNERS_MISMATCH}: the stated {@code internalCorners} differs from the number of
 * reflex vertices of the polygon. The suggested fix is the reflex-vertex count.
 */
public class InternalCornersRule implements ValidationRule {

    private static final double EPS = 1e-9;

    @Override
    public List<Warning> apply(ValidationContext ctx) {
        RoomGeometry g = ctx.geometry();
        if (ctx.internalCorners() == null || !Num.hasUsablePolygon(g)) {
            return List.of();
        }
        double area = Num.signedArea(g.getVertices());
        if (Math.abs(area) <= EPS) {
            return List.of();
        }
        int reflex = reflexCount(g.getVertices(), area > 0 ? 1 : -1);
        if (reflex == ctx.internalCorners()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("stated", ctx.internalCorners());
        params.put("calculated", reflex);
        return List.of(new Warning(WarningCodes.INTERNAL_CORNERS_MISMATCH, Severity.WARNING, ctx.tempId(),
                "internalCorners", params, Map.of("internalCorners", reflex)));
    }

    /** Reflex vertices: cross product sign opposite to the polygon orientation. */
    static int reflexCount(List<RoomGeometry.Vertex> vs, int orientation) {
        int n = vs.size();
        int count = 0;
        for (int i = 0; i < n; i++) {
            RoomGeometry.Vertex a = vs.get((i + n - 1) % n);
            RoomGeometry.Vertex b = vs.get(i);
            RoomGeometry.Vertex c = vs.get((i + 1) % n);
            double cross = (b.getX() - a.getX()) * (c.getY() - b.getY())
                    - (b.getY() - a.getY()) * (c.getX() - b.getX());
            if (cross * orientation < -EPS) {
                count++;
            }
        }
        return count;
    }
}
