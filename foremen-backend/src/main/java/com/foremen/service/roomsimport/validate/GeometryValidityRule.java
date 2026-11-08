package com.foremen.service.roomsimport.validate;

import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.geometry.PolygonChecks;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.List;
import java.util.Map;

/**
 * {@code GEOMETRY_INVALID}: fewer than 3 vertices, zero area or a self-intersecting polygon
 * (Requirement 7.1). The O(n²) intersection test is skipped above {@link #MAX_VERTICES} vertices.
 */
public class GeometryValidityRule implements ValidationRule {

    static final int MAX_VERTICES = 64;
    private static final double EPS = 1e-9;

    @Override
    public List<Warning> apply(ValidationContext ctx) {
        RoomGeometry g = ctx.geometry();
        if (g == null) {
            return List.of();
        }
        if (g.getVertices() == null || g.getVertices().size() < 3) {
            return List.of(warn(ctx, "TOO_FEW_VERTICES"));
        }
        if (!Num.hasUsablePolygon(g)) {
            return List.of(warn(ctx, "NON_FINITE_VERTEX"));
        }
        List<RoomGeometry.Vertex> vs = g.getVertices();
        if (Math.abs(Num.signedArea(vs)) <= EPS) {
            return List.of(warn(ctx, "ZERO_AREA"));
        }
        if (vs.size() <= MAX_VERTICES && selfIntersects(vs)) {
            return List.of(warn(ctx, "SELF_INTERSECTING"));
        }
        return List.of();
    }

    private static Warning warn(ValidationContext ctx, String reason) {
        return new Warning(WarningCodes.GEOMETRY_INVALID, Severity.WARNING, ctx.tempId(), "geometry",
                Map.of("reason", reason), null);
    }

    /** Segment-intersection test over non-adjacent edges (shared {@link PolygonChecks}). */
    static boolean selfIntersects(List<RoomGeometry.Vertex> vs) {
        return PolygonChecks.isSelfIntersecting(vs);
    }
}
