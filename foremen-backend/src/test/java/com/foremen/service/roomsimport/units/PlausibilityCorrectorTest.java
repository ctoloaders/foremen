package com.foremen.service.roomsimport.units;

import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import com.foremen.service.roomsimport.units.PlausibilityInput.Quantity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.foremen.service.roomsimport.units.PlausibilityInput.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FOR-05-02 task 9.2 — Requirement 25.4-25.8: deterministic plausibility correction. */
class PlausibilityCorrectorTest {

    private final PlausibilityCorrector corrector = new PlausibilityCorrector();

    static PlausibilityInput scalars(Object... kv) {
        Map<String, Quantity> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], new Quantity((Double) kv[i + 1], "mm"));
        }
        return new PlausibilityInput("r1", m, null, "mm");
    }

    static RoomGeometry rect(double w, double h) {
        RoomGeometry g = new RoomGeometry();
        g.setVertices(new ArrayList<>(List.of(vertex(0, 0), vertex(w, 0), vertex(w, h), vertex(0, h))));
        List<RoomGeometry.Wall> walls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RoomGeometry.Wall wall = new RoomGeometry.Wall();
            wall.setOpenings(new ArrayList<>());
            walls.add(wall);
        }
        g.setWalls(walls);
        return g;
    }

    static RoomGeometry.Vertex vertex(double x, double y) {
        RoomGeometry.Vertex v = new RoomGeometry.Vertex();
        v.setX(x);
        v.setY(y);
        return v;
    }

    static RoomGeometry.Opening opening(OpeningType t, double w, double h, Double offset) {
        RoomGeometry.Opening o = new RoomGeometry.Opening();
        o.setType(t);
        o.setCount(1);
        o.setWidth(w);
        o.setHeight(h);
        o.setOffset(offset);
        return o;
    }

    private static Warning only(PlausibilityResult r) {
        assertThat(r.warnings()).hasSize(1);
        return r.warnings().get(0);
    }

    @Test
    void inRangeValuesAreUntouchedWithoutWarnings() {
        var in = scalars(CEILING_HEIGHT, 2.6, DOOR_WIDTH, 0.9, FLOOR_AREA, 13.12, PERIMETER, 14.4);
        var r = corrector.correct(in);
        assertThat(r.warnings()).isEmpty();
        assertThat(r.corrected().values()).isEqualTo(in.values());
    }

    @Test
    void ceilingReadInMillimetresAsMetresIsRescaled() {
        var r = corrector.correct(scalars(CEILING_HEIGHT, 2600.0));
        assertThat(r.corrected().value(CEILING_HEIGHT)).isCloseTo(2.6, within(1e-12));
        Warning w = only(r);
        assertThat(w.code()).isEqualTo(WarningCodes.UNIT_CORRECTED);
        assertThat(w.severity()).isEqualTo(Severity.INFO);
        assertThat(w.roomTempId()).isEqualTo("r1");
        assertThat(w.field()).isEqualTo(CEILING_HEIGHT);
        assertThat(w.params()).containsEntry("field", CEILING_HEIGHT).containsEntry("original", 2600.0)
                .containsEntry("originalUnit", "mm").containsEntry("factor", 1000.0);
        assertThat((Double) w.params().get("corrected")).isCloseTo(2.6, within(1e-12));
        assertThat(w.suggestedFix()).containsEntry(CEILING_HEIGHT, 2600.0);
    }

    @Test
    void doorWidthNineHundredBecomesPointNine() {
        var r = corrector.correct(scalars(DOOR_WIDTH, 900.0));
        assertThat(r.corrected().value(DOOR_WIDTH)).isCloseTo(0.9, within(1e-12));
        assertThat(only(r).params()).containsEntry("factor", 1000.0);
    }

    @Test
    void tooSmallMetreReadingIsMultipliedUp() {
        var r = corrector.correct(scalars(CEILING_HEIGHT, 0.26));
        assertThat(r.corrected().value(CEILING_HEIGHT)).isCloseTo(2.6, within(1e-12));
        assertThat((Double) only(r).params().get("factor")).isCloseTo(0.1, within(1e-12));
    }

    @Test
    void statedFloorAreaInSquareMillimetresIsRescaled() {
        var r = corrector.correct(scalars(FLOOR_AREA, 13120000.0));
        assertThat(r.corrected().value(FLOOR_AREA)).isCloseTo(13.12, within(1e-9));
        assertThat(only(r).params()).containsEntry("factor", 1e6);
    }

    @Test
    void ambiguousValueIsClearedWithDimensionOutOfRange() {
        // 500 as a window width: 500/100 = 5 and 500/1000 = 0.5 both fit 0.3..6.0
        var r = corrector.correct(scalars(WINDOW_WIDTH, 500.0));
        assertThat(r.corrected().value(WINDOW_WIDTH)).isNull();
        Warning w = only(r);
        assertThat(w.code()).isEqualTo(WarningCodes.DIMENSION_OUT_OF_RANGE);
        assertThat(w.severity()).isEqualTo(Severity.WARNING);
        assertThat(w.params()).containsEntry("field", WINDOW_WIDTH).containsEntry("original", 500.0)
                .containsEntry("originalUnit", "mm").containsEntry("min", 0.3).containsEntry("max", 6.0);
        assertThat(w.suggestedFix()).isNull();
    }

    @Test
    void noFittingCandidateIsClearedWithDimensionOutOfRange() {
        var r = corrector.correct(scalars(CEILING_HEIGHT, 7777777.0));
        assertThat(r.corrected().value(CEILING_HEIGHT)).isNull();
        assertThat(only(r).code()).isEqualTo(WarningCodes.DIMENSION_OUT_OF_RANGE);
    }

    @Test
    void negativeAndNonFiniteValuesAreCleared() {
        var r = corrector.correct(scalars(CEILING_HEIGHT, -2.6, DOOR_WIDTH, Double.NaN));
        assertThat(r.corrected().value(CEILING_HEIGHT)).isNull();
        assertThat(r.corrected().value(DOOR_WIDTH)).isNull();
        assertThat(r.warnings()).extracting(Warning::code).containsOnly(WarningCodes.DIMENSION_OUT_OF_RANGE);
    }

    @Test
    void doorHeightCrossCheckedAgainstCorrectedCeiling() {
        // doorHeight 2050 -> 2.05 (unique anyway); ceiling 2600 corrected first and used as bound
        var r = corrector.correct(scalars(CEILING_HEIGHT, 2600.0, DOOR_HEIGHT, 2050.0));
        assertThat(r.corrected().value(CEILING_HEIGHT)).isCloseTo(2.6, within(1e-12));
        assertThat(r.corrected().value(DOOR_HEIGHT)).isCloseTo(2.05, within(1e-12));
        assertThat(r.warnings()).hasSize(2);
    }

    @Test
    void ceilingCandidateMustExceedKnownDoorHeight() {
        // ceiling 2600 raw; with doorHeight 2.05 in range: 2.6 > 2.05 fits -> corrected.
        PlausibilityInput in = scalars(CEILING_HEIGHT, 2600.0, DOOR_HEIGHT, 2.05);
        assertThat(corrector.correct(in).corrected().value(CEILING_HEIGHT)).isCloseTo(2.6, within(1e-12));
        // ceiling 190 (cm): ÷100 = 1.9 not above door 2.05 -> nothing fits -> cleared
        PlausibilityInput in2 = scalars(CEILING_HEIGHT, 190.0, DOOR_HEIGHT, 2.05);
        var r = corrector.correct(in2);
        assertThat(r.corrected().value(CEILING_HEIGHT)).isNull();
        assertThat(only(r).code()).isEqualTo(WarningCodes.DIMENSION_OUT_OF_RANGE);
    }

    @Test
    void windowWidthAmbiguityResolvedByWallLength() {
        // 600: ÷100 = 6.0 and ÷1000 = 0.6 both fit the range; the wall is 2.0 m, so only 0.6 fits
        RoomGeometry g = rect(2.0, 3.0);
        g.getWalls().get(0).getOpenings().add(opening(OpeningType.WINDOW, 600.0, 1.2, null));
        var in = new PlausibilityInput("r1", Map.of(), g, "mm");
        var r = corrector.correct(in);
        assertThat(r.corrected().geometry().getWalls().get(0).getOpenings().get(0).getWidth())
                .isCloseTo(0.6, within(1e-12));
        assertThat(only(r).code()).isEqualTo(WarningCodes.UNIT_CORRECTED);
    }

    @Test
    void floorAreaCrossCheckedAgainstPolygonArea() {
        // polygon 4 x 3 = 12 m2; stated 120000 cm2 style reading 1200 -> candidates 12 (÷100) fits
        var in = new PlausibilityInput("r1", Map.of(FLOOR_AREA, new Quantity(1200.0, "m2")), rect(4, 3), "m");
        var r = corrector.correct(in);
        assertThat(r.corrected().value(FLOOR_AREA)).isCloseTo(12.0, within(1e-9));
        // stated 1300 -> 13 does not match polygon 12 within 2% -> cleared
        var in2 = new PlausibilityInput("r1", Map.of(FLOOR_AREA, new Quantity(1300.0, "m2")), rect(4, 3), "m");
        var r2 = corrector.correct(in2);
        assertThat(r2.corrected().value(FLOOR_AREA)).isNull();
        assertThat(r2.warnings()).extracting(Warning::code).containsExactly(WarningCodes.DIMENSION_OUT_OF_RANGE);
    }

    @Test
    void doorAreaIsBoundedByWallArea() {
        // wallArea 30 in range; doorArea 20000 -> ÷1e4 = 2 fits [0,30], ÷100 = 200 doesn't, ÷1e6 = 0.02 fits [0,30]
        // two candidates fit -> ambiguous; 2000 -> ÷100 = 20 fits, ÷1e4 = 0.2 fits -> ambiguous too.
        // 5000000 -> ÷1e6 = 5 fits; ÷1e4 = 500 no; ÷100 no -> unique
        var r = corrector.correct(scalars(WALL_AREA, 30.0, DOOR_AREA, 5000000.0));
        assertThat(r.corrected().value(DOOR_AREA)).isCloseTo(5.0, within(1e-9));
        var amb = corrector.correct(scalars(WALL_AREA, 30.0, DOOR_AREA, 2000.0));
        assertThat(amb.corrected().value(DOOR_AREA)).isNull();
    }

    @Test
    void countsOutOfRangeAreClearedWithoutRescale() {
        var r = corrector.correct(scalars(DOOR_COUNT, 500.0, WINDOW_COUNT, 3.0));
        assertThat(r.corrected().value(DOOR_COUNT)).isNull();
        assertThat(r.corrected().value(WINDOW_COUNT)).isEqualTo(3.0);
        assertThat(only(r).field()).isEqualTo(DOOR_COUNT);
    }

    // ---- geometry -------------------------------------------------------------------------

    @Test
    void geometryInMillimetresReadAsMetresIsRescaledAsOneUnit() {
        RoomGeometry g = rect(4200, 3100);
        g.getWalls().get(0).getOpenings().add(opening(OpeningType.DOOR, 0.9, 2.05, 600.0));
        var r = corrector.correct(new PlausibilityInput("r1", Map.of(), g, "mm"));
        RoomGeometry out = r.corrected().geometry();
        assertThat(out.getVertices().get(1).getX()).isCloseTo(4.2, within(1e-9));
        assertThat(out.getVertices().get(2).getY()).isCloseTo(3.1, within(1e-9));
        assertThat(out.getWalls().get(0).getOpenings().get(0).getOffset()).isCloseTo(0.6, within(1e-9));
        Warning w = only(r);
        assertThat(w.code()).isEqualTo(WarningCodes.UNIT_CORRECTED);
        assertThat(w.field()).isEqualTo("geometry");
        assertThat(w.params()).containsEntry("field", "geometry").containsEntry("factor", 1000.0)
                .containsEntry("originalUnit", "mm");
        assertThat(w.suggestedFix()).containsKey("geometry");
        // input untouched
        assertThat(g.getVertices().get(1).getX()).isEqualTo(4200);
    }

    @Test
    void geometryRescaleIsCrossCheckedWithStatedArea() {
        // 420 x 310: ÷10 -> 42x31 (area 1302 > 500), ÷100 -> 4.2x3.1 = 13.02 fits, ÷1000 -> 0.42x0.31 too small
        var in = new PlausibilityInput("r1", Map.of(FLOOR_AREA, new Quantity(13.02, "m2")), rect(420, 310), "cm");
        var r = corrector.correct(in);
        assertThat(r.corrected().geometry().getVertices().get(1).getX()).isCloseTo(4.2, within(1e-9));
        assertThat(r.corrected().value(FLOOR_AREA)).isEqualTo(13.02);
        assertThat(only(r).params()).containsEntry("factor", 100.0);
    }

    @Test
    void unrescalableGeometryIsClearedWithWarning() {
        var r = corrector.correct(new PlausibilityInput("r1", Map.of(), rect(1.0e9, 1.0e9), "m"));
        assertThat(r.corrected().geometry()).isNull();
        Warning w = only(r);
        assertThat(w.code()).isEqualTo(WarningCodes.DIMENSION_OUT_OF_RANGE);
        assertThat(w.field()).isEqualTo("geometry");
    }

    @Test
    void ambiguousGeometryIsCleared() {
        // 30 x 40 m is too large for a room area (1200 > 500); ÷10 -> 3x4 = 12 fits; ÷100 -> 0.3x0.4 too small => unique
        var unique = corrector.correct(new PlausibilityInput("r1", Map.of(), rect(30, 40), "m"));
        assertThat(unique.corrected().geometry().getVertices().get(1).getX()).isCloseTo(3.0, within(1e-9));
        // 300 x 400: ÷100 -> 3x4 fits, ÷10 -> 30x40 (1200) no, ÷1000 -> 0.3x0.4 no => unique too.
        // 140 x 140 with stated area absent: ÷10 -> 14x14=196 fits, ÷100 -> 1.4x1.4=1.96 fits => ambiguous
        var amb = corrector.correct(new PlausibilityInput("r1", Map.of(), rect(140, 140), "m"));
        assertThat(amb.corrected().geometry()).isNull();
        assertThat(only(amb).code()).isEqualTo(WarningCodes.DIMENSION_OUT_OF_RANGE);
    }

    @Test
    void plausibleGeometryIsUntouched() {
        var in = new PlausibilityInput("r1", Map.of(), rect(4.2, 3.1), "m");
        var r = corrector.correct(in);
        assertThat(r.warnings()).isEmpty();
        assertThat(r.corrected().geometry()).usingRecursiveComparison().isEqualTo(in.geometry());
    }

    @Test
    void wallGapsAreCheckedSeparatelyFromGeometry() {
        RoomGeometry g = rect(4.2, 3.1);
        g.getWalls().get(1).setWallGap(300.0); // mm read as m: ÷1000 = 0.3 fits 0..0.5
        g.getWalls().get(2).setFinishGap(0.2);
        var r = corrector.correct(new PlausibilityInput("r1", Map.of(), g, "mm"));
        assertThat(r.corrected().geometry().getWalls().get(1).getWallGap()).isCloseTo(0.3, within(1e-12));
        assertThat(r.corrected().geometry().getWalls().get(2).getFinishGap()).isEqualTo(0.2);
        Warning w = only(r);
        assertThat(w.field()).isEqualTo("geometry.walls[1].wallGap");
    }

    @Test
    void openingTooWideForWallOrOutOfRangeIsHandled() {
        RoomGeometry g = rect(4.2, 3.1);
        g.getWalls().get(0).getOpenings().add(opening(OpeningType.DOOR, 900.0, 2.05, null)); // -> 0.9
        g.getWalls().get(0).getOpenings().add(opening(OpeningType.DOOR, 9.0e9, 2.05, null)); // cleared -> 0
        var r = corrector.correct(new PlausibilityInput("r1", Map.of(), g, "mm"));
        var os = r.corrected().geometry().getWalls().get(0).getOpenings();
        assertThat(os.get(0).getWidth()).isCloseTo(0.9, within(1e-12));
        assertThat(os.get(1).getWidth()).isZero();
        assertThat(r.warnings()).extracting(Warning::code)
                .containsExactly(WarningCodes.UNIT_CORRECTED, WarningCodes.DIMENSION_OUT_OF_RANGE);
    }

    @Test
    void inputIsNeverMutatedAndNullIsTolerated() {
        RoomGeometry g = rect(4200, 3100);
        g.getWalls().get(0).setWallGap(300.0);
        var in = new PlausibilityInput("r1", Map.of(CEILING_HEIGHT, new Quantity(2600.0, "mm")), g, "mm");
        corrector.correct(in);
        assertThat(in.value(CEILING_HEIGHT)).isEqualTo(2600.0);
        assertThat(g.getVertices().get(1).getX()).isEqualTo(4200);
        assertThat(g.getWalls().get(0).getWallGap()).isEqualTo(300.0);
        assertThat(corrector.correct(null).warnings()).isEmpty();
        assertThat(corrector.correct(new PlausibilityInput(null, null, null, null)).warnings()).isEmpty();
    }

    @Test
    void secondPassIsIdempotent() {
        RoomGeometry g = rect(4200, 3100);
        g.getWalls().get(0).getOpenings().add(opening(OpeningType.DOOR, 0.9, 2.05, 600.0));
        var in = new PlausibilityInput("r1", Map.of(CEILING_HEIGHT, new Quantity(2600.0, "mm"),
                DOOR_WIDTH, new Quantity(900.0, "mm"), FLOOR_AREA, new Quantity(13020000.0, "mm2")), g, "mm");
        var first = corrector.correct(in);
        var second = corrector.correct(first.corrected());
        assertThat(second.warnings()).isEmpty();
        assertThat(second.corrected()).usingRecursiveComparison().isEqualTo(first.corrected());
    }
}
