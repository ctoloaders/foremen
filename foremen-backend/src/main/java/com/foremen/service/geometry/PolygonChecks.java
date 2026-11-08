package com.foremen.service.geometry;

import com.foremen.dao.model.RoomGeometry;

import java.util.List;

/**
 * Pure polygon sanity checks shared by {@code RoomService} (save-time validation) and the rooms
 * import validation / pipeline. Vertices are metric (x, y) points; the polygon is implicitly closed.
 */
public final class PolygonChecks {

    /** A polygon with |area| below this (m²) is treated as degenerate. */
    public static final double MIN_AREA = 1e-6;

    private static final double EPS = 1e-9;

    private PolygonChecks() {
    }

    /** True when every vertex is non-null with finite coordinates (an empty/null list is {@code false}). */
    public static boolean allFinite(List<RoomGeometry.Vertex> vs) {
        if (vs == null || vs.isEmpty()) {
            return false;
        }
        for (RoomGeometry.Vertex v : vs) {
            if (v == null || !Double.isFinite(v.getX()) || !Double.isFinite(v.getY())) {
                return false;
            }
        }
        return true;
    }

    /** Shoelace signed area (positive for counter-clockwise). Requires finite vertices. */
    public static double area(List<RoomGeometry.Vertex> vs) {
        double s = 0;
        for (int i = 0; i < vs.size(); i++) {
            RoomGeometry.Vertex a = vs.get(i);
            RoomGeometry.Vertex b = vs.get((i + 1) % vs.size());
            s += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return s / 2.0;
    }

    /** True when any two non-adjacent edges intersect or touch. Requires finite vertices. */
    public static boolean isSelfIntersecting(List<RoomGeometry.Vertex> vs) {
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

    /**
     * True when the vertices form a usable simple polygon: at least 3 vertices, all finite,
     * |area| &ge; {@link #MIN_AREA}, and no self-intersection.
     */
    public static boolean isValidSimplePolygon(List<RoomGeometry.Vertex> vs) {
        return vs != null && vs.size() >= 3 && allFinite(vs)
                && Math.abs(area(vs)) >= MIN_AREA && !isSelfIntersecting(vs);
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
