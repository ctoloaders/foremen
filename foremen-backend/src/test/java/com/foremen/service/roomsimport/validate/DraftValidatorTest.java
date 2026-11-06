package com.foremen.service.roomsimport.validate;

import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.foremen.service.roomsimport.validate.DraftFixtures.addOpening;
import static com.foremen.service.roomsimport.validate.DraftFixtures.draft;
import static com.foremen.service.roomsimport.validate.DraftFixtures.polygon;
import static com.foremen.service.roomsimport.validate.DraftFixtures.rect;
import static com.foremen.service.roomsimport.validate.DraftFixtures.request;
import static com.foremen.service.roomsimport.validate.DraftFixtures.scalar;
import static com.foremen.service.roomsimport.validate.DraftFixtures.validator;
import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests of {@link DraftValidator} and its rules. Validates: Requirements 7.1-7.4, 3.2, 3.5, 4.4, 21.3. */
class DraftValidatorTest {

    private final DraftValidator validator = validator();
    private static final BigDecimal H = new BigDecimal("2.6");

    private List<Warning> run(DraftRoom d) {
        return validator.validate(d);
    }

    private static Warning only(List<Warning> ws, String code) {
        List<Warning> m = ws.stream().filter(w -> w.code().equals(code)).toList();
        assertThat(m).as(code).hasSize(1);
        return m.get(0);
    }

    @Test
    void cleanRoomHasNoWarnings() {
        DraftRoom d = draft(request(rect(3, 4), H, 0, "Kitchen", 1L), Map.of("floorArea", 12.0, "perimeter", 14.0), 0.9);
        assertThat(run(d)).isEmpty();
    }

    @Test
    void geometryInvalid_tooFewVertices_zeroArea_selfIntersecting() {
        assertThat(only(run(draft(request(polygon(0, 0, 1, 0), H, null, "a", 1L), Map.of(), 0.9)),
                WarningCodes.GEOMETRY_INVALID).params()).containsEntry("reason", "TOO_FEW_VERTICES");
        assertThat(only(run(draft(request(polygon(0, 0, 1, 0, 2, 0), H, null, "a", 1L), Map.of(), 0.9)),
                WarningCodes.GEOMETRY_INVALID).params()).containsEntry("reason", "ZERO_AREA");
        assertThat(only(run(draft(request(polygon(0, 0, 4, 0, 0, 2, 3, 3), H, null, "a", 1L), Map.of(), 0.9)),
                WarningCodes.GEOMETRY_INVALID).params()).containsEntry("reason", "SELF_INTERSECTING");
    }

    @Test
    void areaMismatchCarriesCalculatedAsSuggestedFix() {
        DraftRoom d = draft(request(rect(3, 4), H, null, "a", 1L), Map.of("floorArea", 15.0), 0.9);
        Warning w = only(run(d), WarningCodes.AREA_MISMATCH);
        assertThat(w.field()).isEqualTo("floorArea");
        assertThat(w.roomTempId()).isEqualTo("r1");
        assertThat(w.suggestedFix()).containsEntry("floorArea", new BigDecimal("12.00"));
        assertThat(w.params()).containsEntry("stated", 15.0);
    }

    @Test
    void areaWithinTwoPercentIsNotAMismatch() {
        DraftRoom d = draft(request(rect(3, 4), H, null, "a", 1L), Map.of("floorArea", 12.2), 0.9);
        assertThat(run(d)).extracting(Warning::code).doesNotContain(WarningCodes.AREA_MISMATCH);
    }

    @Test
    void perimeterMismatch() {
        DraftRoom d = draft(request(rect(3, 4), H, null, "a", 1L), Map.of("perimeter", 20.0), 0.9);
        Warning w = only(run(d), WarningCodes.PERIMETER_MISMATCH);
        assertThat(w.suggestedFix()).containsEntry("perimeter", new BigDecimal("14.00"));
    }

    @Test
    void openingsExceedWall() {
        RoomGeometry g = rect(3, 4);
        addOpening(g, 0, OpeningType.WINDOW, 1, 2.0, 2.5);
        addOpening(g, 1, OpeningType.DOOR, 20, 2.0, 0.9);
        Warning w = only(run(draft(request(g, H, null, "a", 1L), Map.of(), 0.9)), WarningCodes.OPENINGS_EXCEED_WALL);
        assertThat(w.params()).containsKeys("openingsArea", "grossWallArea");
    }

    @Test
    void openingsExceedWall_skippedWithoutCeilingHeight() {
        RoomGeometry g = rect(3, 4);
        addOpening(g, 1, OpeningType.DOOR, 50, 2.0, 0.9);
        assertThat(run(draft(request(g, null, null, "a", 1L), Map.of(), 0.9)))
                .extracting(Warning::code).doesNotContain(WarningCodes.OPENINGS_EXCEED_WALL);
    }

    @Test
    void openingTooWide() {
        RoomGeometry g = rect(3, 4);
        addOpening(g, 0, OpeningType.WINDOW, 1, 1.0, 3.5);
        Warning w = only(run(draft(request(g, H, null, "a", 1L), Map.of(), 0.9)), WarningCodes.OPENING_TOO_WIDE);
        assertThat(w.params()).containsEntry("wallIndex", 0).containsEntry("wallLength", 3.0).containsEntry("width", 3.5);
    }

    @Test
    void ceilingHeightOutOfRange() {
        assertThat(run(draft(request(rect(3, 4), new BigDecimal("1.5"), null, "a", 1L), Map.of(), 0.9)))
                .extracting(Warning::code).contains(WarningCodes.CEILING_HEIGHT_OUT_OF_RANGE);
        assertThat(run(draft(request(rect(3, 4), new BigDecimal("6.5"), null, "a", 1L), Map.of(), 0.9)))
                .extracting(Warning::code).contains(WarningCodes.CEILING_HEIGHT_OUT_OF_RANGE);
        assertThat(run(draft(request(rect(3, 4), new BigDecimal("1.8"), null, "a", 1L), Map.of(), 0.9)))
                .extracting(Warning::code).doesNotContain(WarningCodes.CEILING_HEIGHT_OUT_OF_RANGE);
    }

    @Test
    void internalCornersMismatchSuggestsReflexCount() {
        RoomGeometry l = polygon(0, 0, 4, 0, 4, 2, 2, 2, 2, 4, 0, 4); // exactly one reflex vertex
        Warning w = only(run(draft(request(l, H, 3, "a", 1L), Map.of(), 0.9)), WarningCodes.INTERNAL_CORNERS_MISMATCH);
        assertThat(w.suggestedFix()).containsEntry("internalCorners", 1);
        assertThat(run(draft(request(l, H, 1, "a", 1L), Map.of(), 0.9)))
                .extracting(Warning::code).doesNotContain(WarningCodes.INTERNAL_CORNERS_MISMATCH);
    }

    @Test
    void internalCornersIsOrientationIndependent() {
        RoomGeometry cw = polygon(0, 4, 2, 4, 2, 2, 4, 2, 4, 0, 0, 0);
        assertThat(run(draft(request(cw, H, 1, "a", 1L), Map.of(), 0.9)))
                .extracting(Warning::code).doesNotContain(WarningCodes.INTERNAL_CORNERS_MISMATCH);
    }

    @Test
    void dimensionOutOfRange() {
        RoomCreateRequest r = scalar(new BigDecimal("-3"), -1);
        DraftRoom d = draft(r, Map.of("wallArea", 1e12), 0.9);
        assertThat(run(d)).filteredOn(w -> w.code().equals(WarningCodes.DIMENSION_OUT_OF_RANGE))
                .extracting(Warning::field).containsExactlyInAnyOrder("floorArea", "internalCorners", "stated.wallArea");
    }

    @Test
    void lowConfidence() {
        DraftRoom d = draft(request(rect(3, 4), H, null, "a", 1L), Map.of(), 0.5);
        assertThat(run(d)).extracting(Warning::code).containsExactly(WarningCodes.LOW_CONFIDENCE);
    }

    @Test
    void scalarOnlyRoomIsValidatedWithoutGeometry() {
        DraftRoom d = draft(scalar(new BigDecimal("4.20"), null), Map.of("floorArea", 9.0), 0.9);
        assertThat(d.calculated().floorArea()).isEqualByComparingTo("4.20");
        assertThat(run(d)).extracting(Warning::code).containsExactly(WarningCodes.AREA_MISMATCH);
    }

    @Test
    void validationDoesNotMutateDraftAndAppendsWarnings() {
        DraftRoom d = draft(request(rect(3, 4), H, null, "a", 1L), Map.of("floorArea", 99.0), 0.9);
        DraftRoom v = validator.validated(d, List.of());
        assertThat(d.warnings()).isEmpty();
        assertThat(v.warnings()).extracting(Warning::code).containsExactly(WarningCodes.AREA_MISMATCH);
        assertThat(v.room()).isSameAs(d.room());
        assertThat(v.calculated()).isSameAs(d.calculated());
    }

    @Test
    void neverThrowsForNullParts() {
        DraftRoom d = new DraftRoom("r", 0.9, null, null, null, null, null, null);
        assertThat(validator.validate(d)).isEmpty();
    }

    @Test
    void duplicateOfExisting_matchesNormalisedLabelAndType() {
        DraftRoom d = draft(request(rect(3, 4), H, null, "  \u0141azienka ", 5L), Map.of(), 0.9);
        List<ExistingRoomRef> existing = List.of(new ExistingRoomRef(10L, "lazienka", 6L),
                new ExistingRoomRef(11L, "\u0141AZIENKA", 5L));
        Warning w = only(validator.validate(d, existing), WarningCodes.POSSIBLE_DUPLICATE_OF_EXISTING);
        assertThat(w.params()).containsEntry("existingRoomId", 11L).containsEntry("label", "\u0141AZIENKA");
        assertThat(w.roomTempId()).isEqualTo("r1");
    }

    @Test
    void duplicateOfExisting_differentTypeBlankLabelNullTypeOrNoExisting_noWarning() {
        List<ExistingRoomRef> existing = List.of(new ExistingRoomRef(10L, "Kitchen", 5L));
        assertThat(DuplicateOfExistingRule.apply(
                List.of(draft(request(rect(3, 4), H, null, "Kitchen", 6L), Map.of(), 0.9)), existing)).isEmpty();
        assertThat(DuplicateOfExistingRule.apply(
                List.of(draft(request(rect(3, 4), H, null, " ", 5L), Map.of(), 0.9)), existing)).isEmpty();
        assertThat(DuplicateOfExistingRule.apply(
                List.of(draft(request(rect(3, 4), H, null, "Kitchen", null), Map.of(), 0.9)), existing)).isEmpty();
        assertThat(DuplicateOfExistingRule.apply(
                List.of(draft(request(rect(3, 4), H, null, "Kitchen", 5L), Map.of(), 0.9)), List.of())).isEmpty();
    }

    @Test
    void countRule() {
        Warning w = CountRule.evaluate(3, 2).orElseThrow();
        assertThat(w.code()).isEqualTo(WarningCodes.ROOM_COUNT_MISMATCH);
        assertThat(w.params()).containsEntry("stated", 3).containsEntry("detected", 2);
        assertThat(CountRule.evaluate(3, 3)).isEmpty();
        assertThat(CountRule.evaluate(null, 3)).isEmpty();
    }
}
