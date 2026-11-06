package com.foremen.service.roomsimport.validate;

import static com.foremen.service.roomsimport.validate.DraftFixtures.rect;
import static com.foremen.service.roomsimport.validate.DraftFixtures.request;
import static com.foremen.service.roomsimport.validate.DraftFixtures.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

/** Unit tests of {@link DimensionMissingRule}. Validates: Requirement 5.5. */
class DimensionMissingRuleTest {

    private static List<String> fields(List<Warning> ws) {
        assertThat(ws).allSatisfy(w -> assertThat(w.code()).isEqualTo(WarningCodes.DIMENSION_MISSING));
        return ws.stream().map(Warning::field).toList();
    }

    @Test
    void geometryWithCeiling_hasNothingMissing() {
        RoomCreateRequest r = request(rect(3, 4), new BigDecimal("2.6"), 0, "Kitchen", 1L);

        assertThat(DimensionMissingRule.evaluate("r1", r, null)).isEmpty();
    }

    @Test
    void geometryWithoutCeiling_reportsOnlyTheCeiling() {
        RoomCreateRequest r = request(rect(3, 4), null, 0, "Kitchen", 1L);

        assertThat(fields(DimensionMissingRule.evaluate("r1", r, null))).containsExactly("ceilingHeight");
    }

    @Test
    void scalarOnlyRoom_reportsEveryUnreadDimension_neverInventsValues() {
        RoomCreateRequest r = scalar(new BigDecimal("12"), null);

        List<String> missing = fields(DimensionMissingRule.evaluate("r1", r, null));

        assertThat(missing).contains("wallArea", "perimeter", "doorArea", "windowArea",
                "doorCount", "windowCount", "doorHeight", "doorWidth", "windowHeight", "windowWidth",
                "internalCorners").doesNotContain("floorArea", "ceilingHeight");
        assertThat(DimensionMissingRule.evaluate("r1", r, null)).allSatisfy(w -> assertThat(w.roomTempId()).isEqualTo("r1"));
    }
}
