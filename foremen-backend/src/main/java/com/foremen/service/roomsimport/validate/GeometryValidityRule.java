package com.foremen.service.roomsimport.validate;

import com.foremen.dao.model.RoomGeometry;
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

    /** Segment-intersection test over non-adjacent edges. */
    static boolean selfIntersects(List<RoomGeometry.Vertex> vs) {
        int n = vs.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean adjacent = j == i + 1 || (i == 0 && j == n - 1);
                if (adjacent) {
                    continue;
                }
                if (intersect(vs.get(i), vs.get((i + 1) % n), vs.get(j), vs.get((j + 1) % n))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean intersect(RoomGeometry.Vertex p1, RoomGeometry.Vertex p2,
                                     RoomGeometry.Vertex p3, RoomGeometry.Vertex p4) {
        double d1 = cross(p3, p4, p1);
        double d2 = cross(p3, p4, p2);
        double d3 = cross(p1, p2, p3);
        double d4 = cross(p1, p2, p4);
        if (((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS))
                && ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))) {
            return true;
        }
        return (Math.abs(d1) <= EPS && onSegment(p3, p4, p1))
                || (Math.abs(d2) <= EPS && onSegment(p3, p4, p2))
                || (Math.abs(d3) <= EPS && onSegment(p1, p2, p3))
                || (Math.abs(d4) <= EPS && onSegment(p1, p2, p4));
    }

    private static double cross(RoomGeometry.Vertex a, RoomGeometry.Vertex b, RoomGeometry.Vertex c) {
        return (b.getX() - a.getX()) * (c.getY() - a.getY()) - (b.getY() - a.getY()) * (c.getX() - a.getX());
    }

    private static boolean onSegment(RoomGeometry.Vertex a, RoomGeometry.Vertex b, RoomGeometry.Vertex p) {
        return p.getX() >= Math.min(a.getX(), b.getX()) - EPS && p.getX() <= Math.max(a.getX(), b.getX()) + EPS
                && p.getY() >= Math.min(a.getY(), b.getY()) - EPS && p.getY() <= Math.max(a.getY(), b.getY()) + EPS;
    }
}
