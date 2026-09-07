package com.foremen.service;

import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.model.RoomMetrics;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link RoomCalculationService} determinism (FOR-04-14).
 *
 * <p>Feature: FOR-04-14-room, Property 7
 *
 * <p>Property 7: two calculations over the same geometry (and ceiling height) produce identical
 * metrics. The engine is documented as deterministic and side-effect free (Requirement 3.5), so
 * calling {@link RoomCalculationService#calculate(RoomGeometry, BigDecimal)} twice with the same
 * inputs must yield {@link RoomMetrics} results that are equal by record {@code equals}.
 *
 * Validates: Requirements 3.5
 */
class RoomCalculationDeterminismPropertyTest {

    private final RoomCalculationService service = new RoomCalculationService();

    /**
     * Validates: Requirements 3.5
     * Two calculations over the same geometry and ceiling height produce identical metrics.
     */
    @Property(tries = 100)
    void twoCalculationsOverSameGeometryProduceIdenticalMetrics(
            @ForAll("geometries") RoomGeometry geometry,
            @ForAll("ceilingHeights") BigDecimal ceilingHeight) {

        RoomMetrics first = service.calculate(geometry, ceilingHeight);
        RoomMetrics second = service.calculate(geometry, ceilingHeight);

        assertThat(first).isEqualTo(second);
    }

    // --- Generators ---

    @Provide
    Arbitrary<BigDecimal> ceilingHeights() {
        // Include null (treated as zero) plus a spread of positive heights.
        Arbitrary<BigDecimal> positive = Arbitraries.doubles()
                .between(0.5, 5.0)
                .map(d -> BigDecimal.valueOf(d).setScale(2, java.math.RoundingMode.HALF_UP));
        return Arbitraries.oneOf(positive, Arbitraries.just(null));
    }

    @Provide
    Arbitrary<RoomGeometry> geometries() {
        Arbitrary<List<RoomGeometry.Vertex>> vertices = vertex().list().ofMinSize(3).ofMaxSize(8);
        Arbitrary<List<RoomGeometry.Wall>> walls = wall().list().ofMinSize(0).ofMaxSize(8);
        return Combinators.combine(vertices, walls).as((vs, ws) -> {
            RoomGeometry geometry = new RoomGeometry();
            geometry.setVertices(vs);
            geometry.setWalls(ws);
            return geometry;
        });
    }

    private Arbitrary<RoomGeometry.Vertex> vertex() {
        Arbitrary<Double> coord = Arbitraries.doubles().between(-100.0, 100.0);
        return Combinators.combine(coord, coord).as((x, y) -> {
            RoomGeometry.Vertex v = new RoomGeometry.Vertex();
            v.setX(x);
            v.setY(y);
            return v;
        });
    }

    private Arbitrary<RoomGeometry.Wall> wall() {
        Arbitrary<Double> gap = Arbitraries.oneOf(
                Arbitraries.doubles().between(0.0, 10.0),
                Arbitraries.just(null));
        Arbitrary<List<RoomGeometry.Opening>> openings = opening().list().ofMinSize(0).ofMaxSize(4);
        return Combinators.combine(gap, gap, openings).as((wallGap, finishGap, ops) -> {
            RoomGeometry.Wall w = new RoomGeometry.Wall();
            w.setWallGap(wallGap);
            w.setFinishGap(finishGap);
            w.setOpenings(ops);
            return w;
        });
    }

    private Arbitrary<RoomGeometry.Opening> opening() {
        Arbitrary<OpeningType> type = Arbitraries.of(OpeningType.class);
        Arbitrary<Integer> count = Arbitraries.integers().between(1, 5);
        Arbitrary<Double> height = Arbitraries.doubles().between(0.1, 5.0);
        Arbitrary<Double> width = Arbitraries.doubles().between(0.1, 5.0);
        return Combinators.combine(type, count, height, width).as((t, c, h, wdt) -> {
            RoomGeometry.Opening o = new RoomGeometry.Opening();
            o.setType(t);
            o.setCount(c);
            o.setHeight(h);
            o.setWidth(wdt);
            return o;
        });
    }
}
