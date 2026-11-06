package com.foremen.service.roomsimport.validate;

import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.RoomCalculationService;
import com.foremen.service.model.RoomMetrics;
import com.foremen.service.roomsimport.geometry.GeometryNormalizer;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.foremen.service.roomsimport.validate.DraftFixtures.request;
import static com.foremen.service.roomsimport.validate.DraftFixtures.validator;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties 1-3 of FOR-05-02: calculated authority, non-blocking validation, no false mismatch.
 *
 * <p><b>Validates: Requirements 7.2, 7.3, 7.4, 7.5</b>
 */
@Tag("Feature: FOR-05-02-rooms-autoimport, Properties 1-3")
class DraftValidatorPropertyTest {

    private final DraftValidator validator = validator();
    private final RoomCalculationService calc = new RoomCalculationService();

    /** Property 1: calculated == RoomCalculationService output, field by field. */
    @Property(tries = 200)
    void calculatedEqualsCalculationService(@ForAll("anyGeometries") RoomGeometry g, @ForAll("ceilings") BigDecimal h) {
        RoomMetrics m = calc.calculate(g, h);
        var c = validator.calculate(request(g, h, null, "a", 1L));
        assertThat(c.floorArea()).isEqualTo(m.floorArea());
        assertThat(c.perimeter()).isEqualTo(m.perimeter());
        assertThat(c.wallArea()).isEqualTo(m.wallArea());
        assertThat(c.doorArea()).isEqualTo(m.doorArea());
        assertThat(c.windowArea()).isEqualTo(m.windowArea());
        assertThat(c.doorCount()).isEqualTo(m.doorCount());
        assertThat(c.windowCount()).isEqualTo(m.windowCount());
    }

    /** Property 2: warnings only, never throws, never mutates the draft (incl. degenerate polygons). */
    @Property(tries = 300)
    void validationNeverThrowsNorMutates(@ForAll("anyGeometries") RoomGeometry g, @ForAll("ceilings") BigDecimal h,
                                         @ForAll("stated") Map<String, Object> stated,
                                         @ForAll("corners") Integer corners,
                                         @ForAll("confidences") double confidence) {
        RoomCreateRequest room = request(g, h, corners, "Room", 1L);
        DraftRoom d = new DraftRoom("r1", confidence, null, room, validator.calculate(room), stated, List.of(), List.of());
        String before = snapshot(d);
        List<Warning> ws = validator.validate(d, List.of(new ExistingRoomRef(1L, "Room", 1L)));
        DraftRoom v = validator.validated(d, List.of());
        assertThat(ws).isNotNull().doesNotContainNull();
        assertThat(ws).allSatisfy(w -> assertThat(WarningCodes.ALL).contains(w.code()));
        assertThat(snapshot(d)).isEqualTo(before);
        assertThat(v.room()).isSameAs(room);
        assertThat(v.calculated()).isSameAs(d.calculated());
        assertThat(d.warnings()).isEmpty();
    }

    /** Property 3: a stated area/perimeter equal to the calculated one never yields a mismatch. */
    @Property(tries = 300)
    void statedEqualToCalculatedNeverMismatches(@ForAll("rectangles") RoomGeometry g, @ForAll("ceilings") BigDecimal h) {
        RoomCreateRequest room = request(g, h, null, "a", 1L);
        var c = validator.calculate(room);
        Map<String, Object> stated = new HashMap<>();
        stated.put("floorArea", c.floorArea().doubleValue());
        stated.put("perimeter", c.perimeter().doubleValue());
        DraftRoom d = new DraftRoom("r1", 0.9, null, room, c, stated, List.of(), List.of());
        assertThat(validator.validate(d)).extracting(Warning::code)
                .doesNotContain(WarningCodes.AREA_MISMATCH, WarningCodes.PERIMETER_MISMATCH);
    }

    /** Property 3 (unrounded exact stated value is within the 2-decimal rounding of the calculated one). */
    @Property(tries = 300)
    void unroundedStatedAreaNeverMismatches(@ForAll("rectangles") RoomGeometry g) {
        RoomCreateRequest room = request(g, new BigDecimal("2.6"), null, "a", 1L);
        double exact = Math.abs(GeometryNormalizer.signedArea(g.getVertices()));
        DraftRoom d = new DraftRoom("r1", 0.9, null, room, validator.calculate(room),
                Map.of("floorArea", exact), List.of(), List.of());
        assertThat(validator.validate(d)).extracting(Warning::code).doesNotContain(WarningCodes.AREA_MISMATCH);
    }

    private static String snapshot(DraftRoom d) {
        StringBuilder sb = new StringBuilder();
        RoomGeometry g = d.room().geometry();
        sb.append(d.stated()).append(d.calculated()).append(d.warnings());
        if (g.getVertices() != null) {
            g.getVertices().forEach(v -> sb.append('(').append(v.getX()).append(',').append(v.getY()).append(')'));
        }
        if (g.getWalls() != null) {
            g.getWalls().forEach(w -> sb.append(w == null || w.getOpenings() == null ? -1 : w.getOpenings().size()));
        }
        return sb.toString();
    }

    @Provide
    Arbitrary<BigDecimal> ceilings() {
        return Arbitraries.doubles().between(0.5, 8.0).map(d -> BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP));
    }

    @Provide
    Arbitrary<Integer> corners() {
        return Arbitraries.integers().between(-1, 8).injectNull(0.2);
    }

    @Provide
    Arbitrary<Double> confidences() {
        return Arbitraries.doubles().between(0, 1);
    }

    @Provide
    Arbitrary<Map<String, Object>> stated() {
        Arbitrary<Object> val = Arbitraries.doubles().between(-10, 1e11).map(d -> (Object) d);
        return Arbitraries.maps(Arbitraries.of("floorArea", "perimeter", "wallArea", "internalCorners"), val)
                .ofMaxSize(4);
    }

    @Provide
    Arbitrary<RoomGeometry> rectangles() {
        Arbitrary<Double> side = Arbitraries.doubles().between(0.5, 30.0);
        return Arbitraries.doubles().between(0.5, 30.0).flatMap(w -> side.map(l -> DraftFixtures.rect(w, l)));
    }

    /** Arbitrary polygons including degenerate (collinear, duplicate, fewer than 3 vertices) and with openings. */
    @Provide
    Arbitrary<RoomGeometry> anyGeometries() {
        Arbitrary<List<Double>> pts = Arbitraries.doubles().between(-20, 20).list().ofMinSize(0).ofMaxSize(12);
        return pts.flatMap(list -> Arbitraries.integers().between(0, 3).flatMap(openings ->
                Arbitraries.doubles().between(0.1, 6.0).map(width -> {
                    int n = list.size() / 2;
                    double[] xy = new double[n * 2];
                    for (int i = 0; i < n * 2; i++) {
                        xy[i] = list.get(i);
                    }
                    RoomGeometry g = DraftFixtures.polygon(xy);
                    if (n > 0) {
                        for (int k = 0; k < openings; k++) {
                            DraftFixtures.addOpening(g, k % n, k % 2 == 0 ? OpeningType.DOOR : OpeningType.WINDOW,
                                    1, 2.0, width);
                        }
                    }
                    return g;
                })));
    }
}
