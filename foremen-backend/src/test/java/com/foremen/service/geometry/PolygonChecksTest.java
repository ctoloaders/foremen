package com.foremen.service.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.RoomGeometry;

class PolygonChecksTest {

    private static List<RoomGeometry.Vertex> poly(double... xy) {
        List<RoomGeometry.Vertex> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            RoomGeometry.Vertex v = new RoomGeometry.Vertex();
            v.setX(xy[i]);
            v.setY(xy[i + 1]);
            out.add(v);
        }
        return out;
    }

    @Test
    void rectangle_isValid() {
        var r = poly(0, 0, 4, 0, 4, 3, 0, 3);
        assertThat(Math.abs(PolygonChecks.area(r))).isEqualTo(12.0);
        assertThat(PolygonChecks.isSelfIntersecting(r)).isFalse();
        assertThat(PolygonChecks.isValidSimplePolygon(r)).isTrue();
    }

    @Test
    void lShape_isValid() {
        var l = poly(0, 0, 4, 0, 4, 2, 2, 2, 2, 4, 0, 4);
        assertThat(Math.abs(PolygonChecks.area(l))).isEqualTo(12.0);
        assertThat(PolygonChecks.isValidSimplePolygon(l)).isTrue();
    }

    @Test
    void bowTie_isSelfIntersecting() {
        var b = poly(0, 0, 4, 4, 4, 0, 0, 4);
        assertThat(PolygonChecks.isSelfIntersecting(b)).isTrue();
        assertThat(PolygonChecks.isValidSimplePolygon(b)).isFalse();
    }

    @Test
    void zeroArea_isRejected() {
        var line = poly(0, 0, 2, 0, 4, 0);
        assertThat(Math.abs(PolygonChecks.area(line))).isLessThan(PolygonChecks.MIN_AREA);
        assertThat(PolygonChecks.isValidSimplePolygon(line)).isFalse();
    }

    @Test
    void nonFiniteVertex_isRejected() {
        var p = poly(0, 0, 4, 0, Double.NaN, 3);
        assertThat(PolygonChecks.allFinite(p)).isFalse();
        assertThat(PolygonChecks.isValidSimplePolygon(p)).isFalse();
        assertThat(PolygonChecks.isValidSimplePolygon(poly(0, 0, 1, 0))).isFalse();
    }
}
