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
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 4 for FOR-04-14: wall area is non-negative and formula-consistent.
 *
 * <p><b>Property 4:</b> for any geometry and ceiling height,
 * {@code wallArea = max(0, perimeter × height − doorArea − windowArea − wallGap × height)},
 * where the components are the engine's own rounded {@code perimeter}, {@code doorArea},
 * {@code windowArea}, and {@code wallGap}. The result is always {@code >= 0} and rounded to
 * 2 decimals ({@code HALF_UP}).
 *
 * <p><b>Validates: Requirements 3.3</b>
 */
// Feature: FOR-04-14-room, Property 4
class RoomWallAreaPropertyTest {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final RoomCalculationService service = new RoomCalculationService();

    /**
     * For any generated geometry + ceiling height, the engine's {@code wallArea} equals the floored
     * formula recomputed from the engine's own rounded components, and is never negative.
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-04-14-room, Property 4")
    void wallAreaEqualsFlooredFormulaAndIsNonNegative(
            @ForAll("geometries") RoomGeometry geometry,
            @ForAll("ceilingHeights") BigDecimal ceilingHeight) {

        RoomMetrics metrics = service.calculate(geometry, ceilingHeight);

        BigDecimal height = ceilingHeight == null ? BigDecimal.ZERO : ceilingHeight;
        BigDecimal gross = metrics.perimeter().multiply(height);
        BigDecimal gapArea = metrics.wallGap().multiply(height);
        BigDecimal net = gross
                .subtract(metrics.doorArea())
                .subtract(metrics.windowArea())
                .subtract(gapArea);
        BigDecimal expected = round(net.max(BigDecimal.ZERO));

        assertThat(metrics.wallArea())
                .as("wallArea must equal max(0, perimeter*h - doorArea - windowArea - wallGap*h)")
                .isEqualByComparingTo(expected);
        assertThat(metrics.wallArea().signum())
                .as("wallArea must never be negative")
                .isGreaterThanOrEqualTo(0);
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    // ---- Generators -----------------------------------------------------------------------------

    @Provide
    Arbitrary<BigDecimal> ceilingHeights() {
        // Include null and a spread of realistic ceiling heights (mb), rounded to 2 decimals.
        Arbitrary<BigDecimal> positive = Arbitraries.doubles()
                .between(0.0d, 10_000.0d)
                .map(d -> BigDecimal.valueOf(d).setScale(SCALE, ROUNDING));
        return Arbitraries.oneOf(positive, Arbitraries.just(null));
    }

    @Provide
    Arbitrary<RoomGeometry> geometries() {
        Arbitrary<List<RoomGeometry.Vertex>> vertices =
                vertexArbitrary().list().ofMinSize(3).ofMaxSize(8);
        Arbitrary<List<RoomGeometry.Wall>> walls =
                wallArbitrary().list().ofMinSize(0).ofMaxSize(8);
        return Combinators.combine(vertices, walls).as((vs, ws) -> {
            RoomGeometry geometry = new RoomGeometry();
            geometry.setVertices(vs);
            geometry.setWalls(ws);
            return geometry;
        });
    }

    private Arbitrary<RoomGeometry.Vertex> vertexArbitrary() {
        Arbitrary<Double> coord = Arbitraries.doubles().between(-1_000.0d, 1_000.0d);
        return Combinators.combine(coord, coord).as((x, y) -> {
            RoomGeometry.Vertex vertex = new RoomGeometry.Vertex();
            vertex.setX(x);
            vertex.setY(y);
            return vertex;
        });
    }

    private Arbitrary<RoomGeometry.Wall> wallArbitrary() {
        Arbitrary<Double> gap = Arbitraries.oneOf(
                Arbitraries.doubles().between(0.0d, 100.0d),
                Arbitraries.just(null));
        Arbitrary<List<RoomGeometry.Opening>> openings =
                openingArbitrary().list().ofMinSize(0).ofMaxSize(4);
        return Combinators.combine(gap, gap, openings).as((wallGap, finishGap, ops) -> {
            RoomGeometry.Wall wall = new RoomGeometry.Wall();
            wall.setWallGap(wallGap);
            wall.setFinishGap(finishGap);
            wall.setOpenings(new ArrayList<>(ops));
            return wall;
        });
    }

    private Arbitrary<RoomGeometry.Opening> openingArbitrary() {
        Arbitrary<OpeningType> type = Arbitraries.of(OpeningType.DOOR, OpeningType.WINDOW);
        Arbitrary<Integer> count = Arbitraries.integers().between(1, 5);
        Arbitrary<Double> dim = Arbitraries.doubles().between(0.1d, 50.0d);
        return Combinators.combine(type, count, dim, dim).as((t, c, h, w) -> {
            RoomGeometry.Opening opening = new RoomGeometry.Opening();
            opening.setType(t);
            opening.setCount(c);
            opening.setHeight(h);
            opening.setWidth(w);
            return opening;
        });
    }
}
