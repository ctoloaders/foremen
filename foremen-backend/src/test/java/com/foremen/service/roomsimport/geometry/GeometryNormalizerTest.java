package com.foremen.service.roomsimport.geometry;

import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.WarningCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Unit tests for {@link GeometryNormalizer} (FOR-05-02, Requirement 5.4). */
class GeometryNormalizerTest {

    private final GeometryNormalizer normalizer = new GeometryNormalizer();

    static RoomGeometry.Vertex v(double x, double y) {
        RoomGeometry.Vertex vx = new RoomGeometry.Vertex();
        vx.setX(x);
        vx.setY(y);
        return vx;
    }

    static RoomGeometry.Opening opening(OpeningType t, int count, double h, double w, Double offset) {
        RoomGeometry.Opening o = new RoomGeometry.Opening();
        o.setType(t);
        o.setCount(count);
        o.setHeight(h);
        o.setWidth(w);
        o.setOffset(offset);
        return o;
    }

    static RoomGeometry.Wall wall(RoomGeometry.Opening... openings) {
        RoomGeometry.Wall w = new RoomGeometry.Wall();
        w.setOpenings(new ArrayList<>(List.of(openings)));
        return w;
    }

    static RoomGeometry geometry(List<RoomGeometry.Vertex> vs, List<RoomGeometry.Wall> ws) {
        RoomGeometry g = new RoomGeometry();
        g.setVertices(vs);
        g.setWalls(ws);
        return g;
    }

    @Test
    void convertsCentimetresToMetres() {
        RoomGeometry g = geometry(List.of(v(0, 0), v(400, 0), v(400, 300), v(0, 300)),
                List.of(wall(opening(OpeningType.DOOR, 1, 200, 90, 40.0)), wall(), wall(), wall()));

        var r = normalizer.normalize(g, "cm", "r1");

        assertThat(r.geometry().getVertices().get(1).getX()).isCloseTo(4.0, within(1e-9));
        RoomGeometry.Opening o = r.geometry().getWalls().get(0).getOpenings().get(0);
        assertThat(o.getHeight()).isCloseTo(2.0, within(1e-9));
        assertThat(o.getWidth()).isCloseTo(0.9, within(1e-9));
        assertThat(o.getOffset()).isCloseTo(0.4, within(1e-9));
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void removesConsecutiveAndClosingDuplicateVertices() {
        RoomGeometry g = geometry(
                List.of(v(0, 0), v(0, 0), v(4, 0), v(4, 3), v(0, 3), v(0, 0)),
                List.of(wall(), wall(), wall(), wall(), wall(), wall()));

        var r = normalizer.normalize(g, "r1");

        assertThat(r.geometry().getVertices()).hasSize(4);
        assertThat(r.geometry().getWalls()).hasSize(4);
    }

    @Test
    void padsMissingWalls() {
        RoomGeometry g = geometry(List.of(v(0, 0), v(4, 0), v(4, 3), v(0, 3)), List.of(wall()));

        var r = normalizer.normalize(g, "r1");

        assertThat(r.geometry().getWalls()).hasSize(4);
        assertThat(r.geometry().getWalls()).allSatisfy(w -> assertThat(w.getOpenings()).isNotNull());
    }

    @Test
    void trimsExtraWallsWithWarning() {
        RoomGeometry g = geometry(List.of(v(0, 0), v(4, 0), v(4, 3), v(0, 3)),
                List.of(wall(), wall(), wall(), wall(), wall(), wall()));

        var r = normalizer.normalize(g, "r1");

        assertThat(r.geometry().getWalls()).hasSize(4);
        assertThat(r.warnings()).extracting(w -> w.code()).containsExactly(WarningCodes.GEOMETRY_INVALID);
    }

    @Test
    void dropsInvalidOpeningsWithWarning() {
        RoomGeometry g = geometry(List.of(v(0, 0), v(4, 0), v(4, 3), v(0, 3)),
                List.of(wall(opening(OpeningType.DOOR, 1, 2.0, 0.9, null),
                                opening(OpeningType.WINDOW, 1, 1.2, 0.0, null),
                                opening(OpeningType.WINDOW, 0, 1.2, 1.0, null),
                                opening(OpeningType.DOOR, 1, -2.0, 0.9, null)),
                        wall(), wall(), wall()));

        var r = normalizer.normalize(g, "r1");

        assertThat(r.geometry().getWalls().get(0).getOpenings()).hasSize(1);
        assertThat(r.warnings()).hasSize(3)
                .allSatisfy(w -> assertThat(w.code()).isEqualTo(WarningCodes.OPENING_DROPPED));
    }

    @Test
    void clockwisePolygonBecomesCounterClockwiseAndOffsetsMirror() {
        // Clockwise square; wall 0 (0,0)->(0,4) has a door at offset 1.0, width 0.9.
        RoomGeometry g = geometry(List.of(v(0, 0), v(0, 4), v(4, 4), v(4, 0)),
                List.of(wall(opening(OpeningType.DOOR, 1, 2.0, 0.9, 1.0)), wall(), wall(), wall()));

        var r = normalizer.normalize(g, "r1");

        assertThat(GeometryNormalizer.signedArea(r.geometry().getVertices())).isPositive();
        // Original wall 0 is now the last wall (vertex 1 -> vertex 0), measured from the other end.
        RoomGeometry.Opening o = r.geometry().getWalls().get(3).getOpenings().get(0);
        assertThat(o.getOffset()).isCloseTo(4.0 - 1.0 - 0.9, within(1e-9));
    }

    @Test
    void unusableGeometryYieldsNull() {
        assertThat(normalizer.normalize(null, "r1").geometry()).isNull();
        assertThat(normalizer.normalize(geometry(List.of(v(0, 0), v(1, 1)), List.of()), "r1").geometry()).isNull();
        assertThat(normalizer.normalize(geometry(List.of(v(0, 0), v(Double.NaN, 1), v(2, 2)), List.of()), "r1")
                .geometry()).isNull();
    }

    @Test
    void synthesizesRectangleWithInformationalWarning() {
        var r = normalizer.synthesizeRectangle(3.2, 4.1, "r1");

        assertThat(r.geometry().getVertices()).hasSize(4);
        assertThat(r.geometry().getWalls()).hasSize(4);
        assertThat(GeometryNormalizer.signedArea(r.geometry().getVertices())).isCloseTo(3.2 * 4.1, within(1e-9));
        assertThat(r.derivation()).isEqualTo("RECTANGLE_FROM_TEXT");
        assertThat(r.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(WarningCodes.GEOMETRY_SYNTHESIZED);
            assertThat(w.severity().name()).isEqualTo("INFO");
        });
    }

    @Test
    void parsesRectangleDimensionsFromText() {
        assertThat(normalizer.parseRectangleDimensions("Łazienka 3,2 × 4,1 m")).hasValueSatisfying(d -> {
            assertThat(d[0]).isCloseTo(3.2, within(1e-9));
            assertThat(d[1]).isCloseTo(4.1, within(1e-9));
        });
        assertThat(normalizer.parseRectangleDimensions("320 x 410 cm")).hasValueSatisfying(d -> {
            assertThat(d[0]).isCloseTo(3.2, within(1e-9));
            assertThat(d[1]).isCloseTo(4.1, within(1e-9));
        });
        assertThat(normalizer.parseRectangleDimensions("sufit 2,6")).isEmpty();
        assertThat(normalizer.parseRectangleDimensions("3x4 oraz 5x6")).isEmpty();
        assertThat(normalizer.parseRectangleDimensions(null)).isEmpty();
    }
}
