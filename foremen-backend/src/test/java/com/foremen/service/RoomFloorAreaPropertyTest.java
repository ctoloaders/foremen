package com.foremen.service;

import com.foremen.dao.model.RoomGeometry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1 (FOR-04-14): Shoelace floor area.
 *
 * <p>For a generated simple polygon, {@link RoomCalculationService#calculate} produces a
 * {@code floorArea} equal to the absolute shoelace area of the polygon (within the engine's
 * 2-decimal {@code HALF_UP} rounding tolerance). The engine's floor area is geometry-derived and
 * therefore stamped {@code CALCULATED} at the service layer; that source-flag assertion is covered
 * by the integration tests, so this engine-level property verifies the numeric value only.
 *
 * <p>Simple (non-self-intersecting) polygons are produced by sorting a set of random points by
 * their polar angle around the point-set centroid, a construction that always yields a simple
 * polygon once vertices are unique.
 *
 * <p>Feature: FOR-04-14-room, Property 1
 *
 * <p><b>Validates: Requirements 3.1, 7.1</b>
 */
@Tag("Feature: FOR-04-14-room, Property 1")
class RoomFloorAreaPropertyTest {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Tolerance beyond the pure rounding step (2 decimals) to absorb double arithmetic noise. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final RoomCalculationService service = new RoomCalculationService();

    // --- Property: floorArea equals |shoelace area|, rounded to 2 decimals HALF_UP ---

    @Property(tries = 100)
    void floorAreaEqualsAbsoluteShoelaceArea(@ForAll("simplePolygons") List<RoomGeometry.Vertex> vertices) {
        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(List.of());

        BigDecimal actual = service.calculate(geometry, BigDecimal.ZERO).floorArea();

        BigDecimal expected = expectedShoelaceArea(vertices).setScale(SCALE, ROUNDING);

        assertThat(actual).isNotNull();
        assertThat(actual.subtract(expected).abs()).isLessThanOrEqualTo(TOLERANCE);
    }

    /** Independent reference implementation of the absolute shoelace area. */
    private BigDecimal expectedShoelaceArea(List<RoomGeometry.Vertex> vertices) {
        int n = vertices.size();
        double sum = 0.0d;
        for (int i = 0; i < n; i++) {
            RoomGeometry.Vertex current = vertices.get(i);
            RoomGeometry.Vertex next = vertices.get((i + 1) % n);
            sum += (current.getX() * next.getY()) - (next.getX() * current.getY());
        }
        return BigDecimal.valueOf(Math.abs(sum) / 2.0d);
    }

    // --- Providers ---

    /**
     * Generates simple polygons of 3..12 vertices. A set of unique points is sorted by polar angle
     * around its centroid, which produces a non-self-intersecting polygon boundary.
     */
    @Provide
    Arbitrary<List<RoomGeometry.Vertex>> simplePolygons() {
        Arbitrary<Double> coordinate = Arbitraries.doubles().between(-100.0d, 100.0d);
        Arbitrary<Point> points = net.jqwik.api.Combinators.combine(coordinate, coordinate).as(Point::new);
        return points.list().uniqueElements(p -> p.x + "," + p.y).ofMinSize(3).ofMaxSize(12)
                .map(this::toSimplePolygon)
                .filter(polygon -> polygon.size() >= 3);
    }

    /** Orders points by polar angle around the centroid to form a simple polygon. */
    private List<RoomGeometry.Vertex> toSimplePolygon(List<Point> points) {
        double cx = points.stream().mapToDouble(p -> p.x).average().orElse(0.0d);
        double cy = points.stream().mapToDouble(p -> p.y).average().orElse(0.0d);

        List<Point> ordered = new ArrayList<>(points);
        ordered.sort(Comparator.comparingDouble(p -> Math.atan2(p.y - cy, p.x - cx)));

        List<RoomGeometry.Vertex> vertices = new ArrayList<>(ordered.size());
        for (Point p : ordered) {
            RoomGeometry.Vertex vertex = new RoomGeometry.Vertex();
            vertex.setX(p.x);
            vertex.setY(p.y);
            vertices.add(vertex);
        }
        return vertices;
    }

    /** Simple immutable coordinate pair used only for generation. */
    private record Point(double x, double y) {
    }
}
