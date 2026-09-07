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
 * Property 3 for FOR-04-14: opening totals per type.
 *
 * <p><b>Property 3:</b> for any geometry whose walls carry doors and windows, the engine's
 * {@code doorArea} equals the sum over every {@code DOOR} opening of {@code count × height × width},
 * {@code windowArea} equals the same sum over every {@code WINDOW} opening (each rounded to
 * 2 decimals {@code HALF_UP}), {@code doorCount} equals the sum of {@code DOOR} counts, and
 * {@code windowCount} equals the sum of {@code WINDOW} counts. Openings are attached per wall and
 * are aggregated across every wall of the polygon. The service layer stamps {@code doorArea}/
 * {@code windowArea} with source {@code CALCULATED} (the {@link RoomMetrics} record itself carries
 * no source flag).
 *
 * <p>The engine sums each type's raw {@code count × height × width} products in {@code double}
 * space and rounds once at the end, so the reference sum here mirrors that: it accumulates the raw
 * products and applies the same single {@code HALF_UP} rounding to 2 decimals.
 *
 * <p><b>Validates: Requirements 3.4, 7.1</b>
 */
// Feature: FOR-04-14-room, Property 3
class RoomOpeningTotalsPropertyTest {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final RoomCalculationService service = new RoomCalculationService();

    /**
     * For any generated polygon with per-wall openings, the engine's per-type areas equal the
     * summed {@code count × height × width} products (rounded HALF_UP to 2 decimals) and its
     * per-type counts equal the summed counts.
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-04-14-room, Property 3")
    void openingTotalsEqualSummedPerTypeAreasAndCounts(
            @ForAll("geometries") RoomGeometry geometry) {

        RoomMetrics metrics = service.calculate(geometry, BigDecimal.ZERO);

        double doorAreaRaw = 0.0d;
        double windowAreaRaw = 0.0d;
        int doorCount = 0;
        int windowCount = 0;
        for (RoomGeometry.Wall wall : geometry.getWalls()) {
            if (wall == null || wall.getOpenings() == null) {
                continue;
            }
            for (RoomGeometry.Opening opening : wall.getOpenings()) {
                if (opening == null || opening.getType() == null) {
                    continue;
                }
                double area = opening.getCount() * opening.getHeight() * opening.getWidth();
                if (opening.getType() == OpeningType.DOOR) {
                    doorAreaRaw += area;
                    doorCount += opening.getCount();
                } else if (opening.getType() == OpeningType.WINDOW) {
                    windowAreaRaw += area;
                    windowCount += opening.getCount();
                }
            }
        }

        BigDecimal expectedDoorArea = round(BigDecimal.valueOf(doorAreaRaw));
        BigDecimal expectedWindowArea = round(BigDecimal.valueOf(windowAreaRaw));

        assertThat(metrics.doorArea())
                .as("doorArea must equal Σ count×height×width over DOOR openings")
                .isEqualByComparingTo(expectedDoorArea);
        assertThat(metrics.windowArea())
                .as("windowArea must equal Σ count×height×width over WINDOW openings")
                .isEqualByComparingTo(expectedWindowArea);
        assertThat(metrics.doorCount())
                .as("doorCount must equal Σ counts over DOOR openings")
                .isEqualTo(doorCount);
        assertThat(metrics.windowCount())
                .as("windowCount must equal Σ counts over WINDOW openings")
                .isEqualTo(windowCount);
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    // ---- Generators -----------------------------------------------------------------------------

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
