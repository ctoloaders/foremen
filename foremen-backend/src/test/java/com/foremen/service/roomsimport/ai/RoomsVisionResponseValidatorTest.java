package com.foremen.service.roomsimport.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.roomsimport.ai.MeasureResponse.Opening;
import com.foremen.service.roomsimport.ai.MeasureResponse.Vertex;
import com.foremen.service.roomsimport.ai.MeasureResponse.Wall;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Requirement 25: structural unit checks of the measure response. */
class RoomsVisionResponseValidatorTest {

    private static final Measured M = Measured.explicit(2.6, "m");

    private static MeasureResponse response(String geometryUnit, String source, Measured ceiling,
                                            Map<String, Measured> stated, Opening opening) {
        List<Vertex> v = List.of(new Vertex(0.0, 0.0), new Vertex(3.0, 0.0), new Vertex(3.0, 4.0));
        List<Wall> walls = List.of(new Wall(null, null, opening == null ? List.of() : List.of(opening)),
                new Wall(null, null, List.of()), new Wall(null, null, List.of()));
        return new MeasureResponse(geometryUnit, source, v, walls, ceiling, 0, null, stated, true, 0.8);
    }

    private static MeasureResponse valid() {
        return response("m", "EXPLICIT", M, Map.of(), null);
    }

    @Test
    void validResponseHasNoProblem() {
        assertThat(RoomsVisionResponseValidator.validate(valid())).isNull();
    }

    @Test
    void everyLengthUnitIsAccepted() {
        for (String u : List.of("m", "cm", "mm", "ft")) {
            assertThat(RoomsVisionResponseValidator.validate(
                    response(u, "INFERRED", Measured.inferred(260, u), Map.of(), null))).isNull();
        }
    }

    @Test
    void everyAreaUnitIsAcceptedOnAreaFields() {
        for (String u : List.of("m2", "cm2", "mm2")) {
            assertThat(RoomsVisionResponseValidator.validate(
                    response("m", "EXPLICIT", M, Map.of("floorArea", Measured.explicit(13.12, u)), null)))
                    .isNull();
        }
    }

    @Test
    void unknownGeometryUnitIsRejected() {
        assertThat(RoomsVisionResponseValidator.validate(response("km", "EXPLICIT", M, Map.of(), null)))
                .isEqualTo("unknown unit");
    }

    @Test
    void unknownValueUnitIsRejected() {
        assertThat(RoomsVisionResponseValidator.validate(
                response("m", "EXPLICIT", Measured.explicit(2.6, "yd"), Map.of(), null)))
                .isEqualTo("unknown unit");
    }

    @Test
    void missingUnitIsRejected() {
        assertThat(RoomsVisionResponseValidator.validate(
                response("m", "EXPLICIT", new Measured(2.6, null, "EXPLICIT"), Map.of(), null)))
                .isEqualTo("unknown unit");
    }

    @Test
    void lengthUnitOnAreaFieldIsRejected() {
        assertThat(RoomsVisionResponseValidator.validate(response("m", "EXPLICIT", M,
                Map.of("floorArea", Measured.explicit(13.12, "m")), null)))
                .isEqualTo("length unit on an area field");
    }

    @Test
    void areaUnitOnLengthFieldIsRejected() {
        assertThat(RoomsVisionResponseValidator.validate(
                response("m", "EXPLICIT", Measured.explicit(2.6, "m2"), Map.of(), null)))
                .isEqualTo("area unit on a length field");
        assertThat(RoomsVisionResponseValidator.validate(response("m", "EXPLICIT", M,
                Map.of("perimeter", Measured.explicit(14.0, "m2")), null)))
                .isEqualTo("area unit on a length field");
        assertThat(RoomsVisionResponseValidator.validate(response("m2", "EXPLICIT", M, Map.of(), null)))
                .isEqualTo("area unit on a length field");
    }

    @Test
    void missingUnitSourceIsRejected() {
        assertThat(RoomsVisionResponseValidator.validate(response("m", null, M, Map.of(), null)))
                .isEqualTo("missing unitSource");
        assertThat(RoomsVisionResponseValidator.validate(
                response("m", "EXPLICIT", new Measured(2.6, "m", null), Map.of(), null)))
                .isEqualTo("missing unitSource");
        assertThat(RoomsVisionResponseValidator.validate(
                response("m", "EXPLICIT", new Measured(2.6, "m", "GUESSED"), Map.of(), null)))
                .isEqualTo("missing unitSource");
    }

    @Test
    void geometryWithoutUnitIsRejected() {
        assertThat(RoomsVisionResponseValidator.validate(response(null, null, M, Map.of(), null)))
                .isEqualTo("missing geometryUnit");
    }

    @Test
    void openingSizesNeedLengthUnitsAndSources() {
        Opening ok = new Opening("DOOR", 1, Measured.explicit(2050, "mm"), Measured.explicit(900, "mm"),
                Measured.explicit(500, "mm"));
        assertThat(RoomsVisionResponseValidator.validate(response("mm", "EXPLICIT", M, Map.of(), ok))).isNull();

        Opening areaUnit = new Opening("DOOR", 1, Measured.explicit(2050, "mm2"), Measured.explicit(900, "mm"), null);
        assertThat(RoomsVisionResponseValidator.validate(response("mm", "EXPLICIT", M, Map.of(), areaUnit)))
                .isEqualTo("area unit on a length field");

        Opening noSource = new Opening("WINDOW", 1, Measured.explicit(1500, "mm"),
                new Measured(1200.0, "mm", null), null);
        assertThat(RoomsVisionResponseValidator.validate(response("mm", "EXPLICIT", M, Map.of(), noSource)))
                .isEqualTo("missing unitSource");
    }

    @Test
    void openingCountMustNotBeNegative() {
        Opening neg = new Opening("DOOR", -1, M, M, null);
        assertThat(RoomsVisionResponseValidator.validate(response("m", "EXPLICIT", M, Map.of(), neg)))
                .isEqualTo("negative count");
    }

    @Test
    void countsInStatedCarryNoUnit() {
        Map<String, Measured> stated = new LinkedHashMap<>();
        stated.put("doorCount", new Measured(2.0, null, null));
        stated.put("windowCount", new Measured(1.0, null, null));
        assertThat(RoomsVisionResponseValidator.validate(response("m", "EXPLICIT", M, stated, null))).isNull();
    }

    @Test
    void nullValuesMeanNotPresentAndSkipUnitChecks() {
        Map<String, Measured> stated = new LinkedHashMap<>();
        stated.put("floorArea", null);
        stated.put("perimeter", new Measured(null, null, null));
        assertThat(RoomsVisionResponseValidator.validate(
                response("m", "EXPLICIT", new Measured(null, null, null), stated, null))).isNull();
    }

    // --- Template answers (autoimport Round 2) ---------------------------------------------

    private static MeasureResponse withTemplate(MeasureResponse.Template t) {
        return new MeasureResponse("mm", "EXPLICIT", List.of(), List.of(), M, 0, null, Map.of(), true, 0.8, t);
    }

    private static Map<String, Measured> lDims() {
        Map<String, Measured> d = new LinkedHashMap<>();
        d.put("width", Measured.explicit(5000, "mm"));
        d.put("length", Measured.explicit(4000, "mm"));
        d.put("cutWidth", Measured.explicit(2000, "mm"));
        d.put("cutLength", Measured.explicit(2000, "mm"));
        return d;
    }

    @Test
    void templateAnswerWithoutVerticesIsValid() {
        assertThat(RoomsVisionResponseValidator.validate(
                withTemplate(new MeasureResponse.Template("L_SHAPE", 90, lDims(), 0.9)))).isNull();
        assertThat(RoomsVisionResponseValidator.validate(
                withTemplate(new MeasureResponse.Template("L_SHAPE", null, lDims(), null)))).isNull();
    }

    @Test
    void unknownTemplateIdIsInvalid() {
        assertThat(RoomsVisionResponseValidator.validate(
                withTemplate(new MeasureResponse.Template("Z_SHAPE", 0, lDims(), 0.9))))
                .isEqualTo("unknown template id");
        assertThat(RoomsVisionResponseValidator.validate(
                withTemplate(new MeasureResponse.Template(null, 0, lDims(), 0.9))))
                .isEqualTo("unknown template id");
    }

    @Test
    void missingTemplateDimIsInvalid() {
        Map<String, Measured> dims = lDims();
        dims.remove("cutLength");
        assertThat(RoomsVisionResponseValidator.validate(
                withTemplate(new MeasureResponse.Template("L_SHAPE", 0, dims, 0.9))))
                .isEqualTo("missing template dimension");
    }

    @Test
    void templateDimNeedsLengthUnitAndSource() {
        Map<String, Measured> dims = lDims();
        dims.put("width", Measured.explicit(5000, "mm2"));
        assertThat(RoomsVisionResponseValidator.validate(
                withTemplate(new MeasureResponse.Template("L_SHAPE", 0, dims, 0.9))))
                .isEqualTo("area unit on a length field");
        dims.put("width", new Measured(5000.0, "mm", null));
        assertThat(RoomsVisionResponseValidator.validate(
                withTemplate(new MeasureResponse.Template("L_SHAPE", 0, dims, 0.9))))
                .isEqualTo("missing unitSource");
    }

    @Test
    void templateRotationMustBeAQuarterTurn() {
        for (int bad : new int[] {45, -90, 360}) {
            assertThat(RoomsVisionResponseValidator.validate(
                    withTemplate(new MeasureResponse.Template("L_SHAPE", bad, lDims(), 0.9))))
                    .isEqualTo("invalid template rotation");
        }
    }

    @Test
    void textOnlyResponseNeedsNoGeometryUnit() {
        MeasureResponse r = new MeasureResponse(null, null, List.of(), List.of(), M, null, null,
                Map.of("floorArea", Measured.explicit(12.0, "m2")), false, 0.7);
        assertThat(RoomsVisionResponseValidator.validate(r)).isNull();
    }
}
