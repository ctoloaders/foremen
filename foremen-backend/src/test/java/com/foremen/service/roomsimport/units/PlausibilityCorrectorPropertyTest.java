package com.foremen.service.roomsimport.units;

import com.foremen.config.roomsimport.RoomsImportProperties.Plausibility;
import com.foremen.config.roomsimport.RoomsImportProperties.Range;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import com.foremen.service.roomsimport.units.PlausibilityInput.Quantity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.foremen.service.roomsimport.units.PlausibilityInput.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Properties 13-15 of FOR-05-02-rooms-autoimport.
 *
 * <p><b>Validates: Requirements 25.4, 25.5, 25.8</b>
 */
@Tag("Feature: FOR-05-02-rooms-autoimport, Properties 13-15")
class PlausibilityCorrectorPropertyTest {

    private final PlausibilityCorrector corrector = new PlausibilityCorrector();
    private final Plausibility ranges = Plausibility.defaults();

    private static final List<String> LENGTH_FIELDS = List.of(CEILING_HEIGHT, DOOR_HEIGHT, DOOR_WIDTH,
            WINDOW_HEIGHT, WINDOW_WIDTH, PERIMETER);

    private Range rangeOf(String field) {
        return switch (field) {
            case CEILING_HEIGHT -> ranges.ceilingHeight();
            case DOOR_HEIGHT -> ranges.doorHeight();
            case DOOR_WIDTH -> ranges.doorWidth();
            case WINDOW_HEIGHT -> ranges.windowHeight();
            case WINDOW_WIDTH -> ranges.windowWidth();
            case PERIMETER -> ranges.perimeter();
            case FLOOR_AREA -> ranges.floorArea();
            default -> throw new IllegalArgumentException(field);
        };
    }

    @Provide
    Arbitrary<String> lengthFields() {
        return Arbitraries.of(LENGTH_FIELDS);
    }

    @Provide
    Arbitrary<Double> anyDouble() {
        return Arbitraries.oneOf(
                Arbitraries.doubles().between(-1e9, 1e9),
                Arbitraries.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0));
    }

    private Arbitrary<Double> nullableDouble() {
        return anyDouble().injectNull(0.2);
    }

    @Provide
    Arbitrary<PlausibilityInput> anyInput() {
        Arbitrary<Map<String, Quantity>> scalars = Arbitraries.of(CEILING_HEIGHT, NARROW_PLACE, FLOOR_AREA,
                        WALL_AREA, PERIMETER, DOOR_AREA, WINDOW_AREA, DOOR_HEIGHT, DOOR_WIDTH, WINDOW_HEIGHT,
                        WINDOW_WIDTH, DOOR_COUNT, WINDOW_COUNT)
                .flatMap(k -> nullableDouble().map(v -> Map.entry(k, new Quantity(v, "mm"))))
                .list().ofMaxSize(13)
                .map(es -> {
                    Map<String, Quantity> m = new LinkedHashMap<>();
                    es.forEach(e -> m.put(e.getKey(), e.getValue()));
                    return m;
                });
        return Combinators.combine(scalars, geometries()).as((m, g) -> new PlausibilityInput("r", m, g, "mm"));
    }

    @Provide
    Arbitrary<RoomGeometry> geometries() {
        Arbitrary<Double> coord = anyDouble();
        Arbitrary<RoomGeometry.Opening> opening = Combinators.combine(
                Arbitraries.of(OpeningType.values()), anyDouble(), anyDouble(),
                nullableDouble()).as((t, w, h, off) -> {
            RoomGeometry.Opening o = new RoomGeometry.Opening();
            o.setType(t);
            o.setCount(1);
            o.setWidth(w);
            o.setHeight(h);
            o.setOffset(off);
            return o;
        });
        Arbitrary<RoomGeometry.Wall> wall = Combinators.combine(
                nullableDouble(), nullableDouble(),
                opening.list().ofMaxSize(3)).as((g1, g2, os) -> {
            RoomGeometry.Wall w = new RoomGeometry.Wall();
            w.setWallGap(g1);
            w.setFinishGap(g2);
            w.setOpenings(new ArrayList<>(os));
            return w;
        });
        Arbitrary<RoomGeometry.Vertex> vertex = Combinators.combine(coord, coord).as((x, y) -> {
            RoomGeometry.Vertex v = new RoomGeometry.Vertex();
            v.setX(x);
            v.setY(y);
            return v;
        });
        Arbitrary<RoomGeometry> geometry = Combinators.combine(vertex.list().ofMinSize(0).ofMaxSize(6),
                wall.list().ofMaxSize(6)).as((vs, ws) -> {
            RoomGeometry g = new RoomGeometry();
            g.setVertices(new ArrayList<>(vs));
            g.setWalls(new ArrayList<>(ws));
            return g;
        });
        return Arbitraries.oneOf(geometry, Arbitraries.just(null));
    }

    // Property 13: idempotence -----------------------------------------------------------------

    @Property(tries = 300)
    void correctionIsIdempotent(@ForAll("anyInput") PlausibilityInput in) {
        var first = corrector.correct(in);
        var second = corrector.correct(first.corrected());
        assertThat(second.warnings()).extracting(Warning::code).doesNotContain(WarningCodes.UNIT_CORRECTED);
        // Double::equals treats NaN as equal to itself (AssertJ's default comparator does not)
        assertThat(second.corrected()).usingRecursiveComparison()
                .withEqualsForType(Double::equals, Double.class)
                .isEqualTo(first.corrected());
    }

    @Property(tries = 300)
    void neverThrowsAndNeverMutatesInput(@ForAll("anyInput") PlausibilityInput in) {
        Map<String, Quantity> before = new LinkedHashMap<>(in.values());
        int vertices = in.geometry() == null || in.geometry().getVertices() == null ? -1
                : in.geometry().getVertices().size();
        Double firstX = in.geometry() == null || in.geometry().getVertices() == null
                || in.geometry().getVertices().isEmpty() ? null : in.geometry().getVertices().get(0).getX();
        var result = corrector.correct(in);
        assertThat(result).isNotNull();
        assertThat(result.warnings()).isNotNull();
        assertThat(in.values()).isEqualTo(before);
        if (vertices >= 0) {
            assertThat(in.geometry().getVertices()).hasSize(vertices);
            if (firstX != null) {
                assertThat(in.geometry().getVertices().get(0).getX()).isEqualTo(firstX);
            }
        }
    }

    // Property 14: in-range untouched ----------------------------------------------------------

    @Property(tries = 300)
    void inRangeValueIsUntouchedWithoutWarning(@ForAll("lengthFields") String field,
                                               @ForAll("unit") double t) {
        Range r = rangeOf(field);
        double v = r.min() + t * (r.max() - r.min());
        var result = corrector.correct(new PlausibilityInput("r", Map.of(field, new Quantity(v, "m")), null, "m"));
        assertThat(result.corrected().value(field)).isEqualTo(v);
        assertThat(result.warnings()).isEmpty();
    }

    @Property(tries = 100)
    void inRangeAreaIsUntouched(@ForAll("unit") double t) {
        Range r = ranges.floorArea();
        double v = r.min() + t * (r.max() - r.min());
        var result = corrector.correct(new PlausibilityInput("r", Map.of(FLOOR_AREA, new Quantity(v, "m2")), null, "m2"));
        assertThat(result.corrected().value(FLOOR_AREA)).isEqualTo(v);
        assertThat(result.warnings()).isEmpty();
    }

    @Provide
    Arbitrary<Double> unit() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }

    // Property 15: rescale round-trip ------------------------------------------------------------

    @Property(tries = 400)
    void rescaledValueIsCorrectedBackOrReportedAmbiguous(@ForAll("lengthFields") String field,
                                                         @ForAll("unit") double t,
                                                         @ForAll("factor") int f) {
        Range r = rangeOf(field);
        double v = r.min() + t * (r.max() - r.min());
        double scaled = v * f;
        net.jqwik.api.Assume.that(!r.contains(scaled));
        var result = corrector.correct(new PlausibilityInput("r", Map.of(field, new Quantity(scaled, "mm")), null, "mm"));
        Warning w = result.warnings().get(0);
        assertThat(result.warnings()).hasSize(1);
        Double out = result.corrected().value(field);
        if (WarningCodes.UNIT_CORRECTED.equals(w.code())) {
            // the candidate v/f always fits, so a unique fit must be exactly v
            assertThat(out).isCloseTo(v, within(Math.abs(v) * 1e-9));
            assertThat((Double) w.params().get("factor")).isEqualTo((double) f);
            assertThat(w.suggestedFix()).containsEntry(field, scaled);
        } else {
            assertThat(w.code()).isEqualTo(WarningCodes.DIMENSION_OUT_OF_RANGE);
            assertThat(out).isNull();
        }
    }

    /** With a unique-fit field (ceiling) the round-trip must always succeed. */
    @Property(tries = 200)
    void ceilingRoundTripIsAlwaysUnique(@ForAll("unit") double t, @ForAll("factor") int f) {
        Range r = ranges.ceilingHeight();
        double v = r.min() + t * (r.max() - r.min());
        var result = corrector.correct(new PlausibilityInput("r", Map.of(CEILING_HEIGHT, new Quantity(v * f, "mm")),
                null, "mm"));
        assertThat(result.corrected().value(CEILING_HEIGHT)).isCloseTo(v, within(v * 1e-9));
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0).code()).isEqualTo(WarningCodes.UNIT_CORRECTED);
        assertThat(result.warnings().get(0).params()).containsEntry("factor", (double) f);
    }

    @Provide
    Arbitrary<Integer> factor() {
        return Arbitraries.of(10, 100, 1000);
    }
}
