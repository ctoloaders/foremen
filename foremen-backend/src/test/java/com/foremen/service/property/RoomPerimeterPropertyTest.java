package com.foremen.service.property;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.RoomCalculationService;
import com.foremen.service.model.RoomMetrics;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the perimeter derivation of
 * {@link RoomCalculationService#calculate(RoomGeometry, BigDecimal)} (Requirements 3.2, 7.1).
 *
 * <p>The engine is a pure, side-effect-free function of its arguments, so these tests instantiate
 * {@link RoomCalculationService} directly and feed it generated polygons.
 *
 * <p>Property 2: perimeter equals the sum of the Euclidean lengths of every wall edge, wall
 * {@code i} joining vertex {@code i} to vertex {@code (i + 1) mod n}. The engine rounds the result
 * to {@code NUMERIC(12,2)} with {@code HALF_UP}, so the returned value is compared against the
 * independently summed edge lengths rounded the same way. The service layer stamps this metric with
 * source {@code CALCULATED} (the {@link RoomMetrics} record itself carries no source flag).
 *
 * Feature: FOR-04-14-room, Property 2
 * Validates: Requirements 3.2, 7.1
 */
@Tag("Feature: FOR-04-14-room, Property 2")
class RoomPerimeterPropertyTest {

    /** Decimal scale of every persisted metric ({@code NUMERIC(12,2)}). */
    private static final int SCALE = 2;

    /** Rounding mode the engine applies to every metric. */
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Coordinate range kept modest so summed edge lengths stay within {@code NUMERIC(12,2)}. */
    private static final double COORD_MIN = -1_000.0d;
    private static final double COORD_MAX = 1_000.0d;

    private final RoomCalculationService service = new RoomCalculationService();

    /** A single vertex with coordinates in a bounded, finite range. */
    @Provide
    Arbitrary<RoomGeometry.Vertex> vertices() {
        Arbitrary<Double> coordinate = Arbitraries.doubles()
                .between(COORD_MIN, COORD_MAX)
                .ofScale(4);
        return Combinators.combine(coordinate, coordinate).as((x, y) -> {
            RoomGeometry.Vertex vertex = new RoomGeometry.Vertex();
            vertex.setX(x);
            vertex.setY(y);
            return vertex;
        });
    }

    /** A polygon of at least 3 vertices (a valid closed shape). */
    @Provide
    Arbitrary<List<RoomGeometry.Vertex>> polygons() {
        return vertices().list().ofMinSize(3).ofMaxSize(12);
    }

    // Feature: FOR-04-14-room, Property 2
    // For any generated polygon, calculate(...).perimeter equals the sum of the Euclidean lengths
    // of the wall edges (vertex i -> vertex (i+1) mod n), rounded to 2 decimals HALF_UP.
    // Validates: Requirements 3.2, 7.1
    @Property(tries = 100)
    void perimeterEqualsSummedEuclideanEdgeLengths(
            @ForAll("polygons") List<RoomGeometry.Vertex> vertices) {
        RoomGeometry geometry = new RoomGeometry();
        geometry.setVertices(vertices);
        geometry.setWalls(new ArrayList<>());

        RoomMetrics metrics = service.calculate(geometry, BigDecimal.ZERO);

        BigDecimal expected = summedEdgeLengths(vertices).setScale(SCALE, ROUNDING);

        assertThat(metrics.perimeter()).isEqualByComparingTo(expected);
    }

    /**
     * Reference perimeter: sum of the Euclidean lengths of each edge joining vertex {@code i} to
     * vertex {@code (i + 1) mod n}, closing the polygon.
     */
    private BigDecimal summedEdgeLengths(List<RoomGeometry.Vertex> vertices) {
        int n = vertices.size();
        double sum = 0.0d;
        for (int i = 0; i < n; i++) {
            RoomGeometry.Vertex current = vertices.get(i);
            RoomGeometry.Vertex next = vertices.get((i + 1) % n);
            double dx = next.getX() - current.getX();
            double dy = next.getY() - current.getY();
            sum += Math.hypot(dx, dy);
        }
        return BigDecimal.valueOf(sum);
    }
}
