package com.foremen.service.roomsimport.validate;

import com.foremen.dao.model.RoomGeometry;

import java.math.BigDecimal;
import java.util.List;

/** Small null-safe numeric / polygon helpers shared by the validation rules. */
final class Num {
    private Num() {}

    /** Finite double of a {@link Number}, otherwise {@code null}. */
    static Double toDouble(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    static double dbl(BigDecimal b) {
        return b == null ? 0.0 : b.doubleValue();
    }

    /** True when the polygon has at least 3 vertices, all non-null with finite coordinates. */
    static boolean hasUsablePolygon(RoomGeometry g) {
        if (g == null || g.getVertices() == null || g.getVertices().size() < 3) {
            return false;
        }
        for (RoomGeometry.Vertex v : g.getVertices()) {
            if (v == null || !Double.isFinite(v.getX()) || !Double.isFinite(v.getY())) {
                return false;
            }
        }
        return true;
    }

    /** Shoelace signed area (positive for counter-clockwise). */
    static double signedArea(List<RoomGeometry.Vertex> vs) {
        double s = 0;
        for (int i = 0; i < vs.size(); i++) {
            RoomGeometry.Vertex a = vs.get(i);
            RoomGeometry.Vertex b = vs.get((i + 1) % vs.size());
            s += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return s / 2.0;
    }

    /** Length of the polygon edge {@code i -> i+1}. */
    static double edgeLength(List<RoomGeometry.Vertex> vs, int i) {
        RoomGeometry.Vertex a = vs.get(i);
        RoomGeometry.Vertex b = vs.get((i + 1) % vs.size());
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }
}
