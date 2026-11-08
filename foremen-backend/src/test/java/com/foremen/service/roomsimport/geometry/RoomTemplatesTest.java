package com.foremen.service.roomsimport.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.roomsimport.geometry.RoomTemplates.Pt;
import com.foremen.service.roomsimport.geometry.RoomTemplates.Template;
import com.foremen.service.roomsimport.geometry.RoomTemplates.TemplateId;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoomTemplatesTest {

    private static final double TOL = 1e-6;

    @Test
    void javaBuildMatchesFrontendFixtureForAll24Entries() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/roomsimport/room-templates.json")) {
            root = new ObjectMapper().readTree(in);
        }
        assertThat(root.size()).isEqualTo(24);
        for (JsonNode e : root) {
            TemplateId id = TemplateId.valueOf(e.get("id").asText());
            int turns = e.get("quarterTurns").asInt();
            Map<String, Double> dims = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = e.get("dims").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> d = it.next();
                dims.put(d.getKey(), d.getValue().asDouble());
            }
            List<Pt> actual = RoomTemplates.build(id, dims, turns);
            JsonNode expected = e.get("vertices");
            String label = id + " turns=" + turns;
            assertThat(actual).as(label).hasSize(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertThat(actual.get(i).x()).as(label + " x" + i).isCloseTo(expected.get(i).get("x").asDouble(), within(TOL));
                assertThat(actual.get(i).y()).as(label + " y" + i).isCloseTo(expected.get(i).get("y").asDouble(), within(TOL));
            }
        }
    }

    @Test
    void defaultsAreValidAndRotationFourTimesIsIdentity() {
        for (Template t : RoomTemplates.all()) {
            Map<String, Double> d = t.defaultDims();
            assertThat(t.validate(d)).as(t.id().name()).isNull();
            List<Pt> base = RoomTemplates.rotateOutline(t.build(d), 0);
            List<Pt> r = base;
            for (int i = 0; i < 4; i++) {
                r = RoomTemplates.rotateOutline(r, 1);
            }
            assertThat(r).as(t.id().name()).isEqualTo(base);
        }
    }

    @Test
    void validationRejectsNonPositiveAndOversizedCuts() {
        Template l = RoomTemplates.get(TemplateId.L_SHAPE);
        assertThat(l.validate(Map.of("width", 5.0, "length", 4.0, "cutWidth", 2.0, "cutLength", 2.0))).isNull();
        assertThat(l.validate(Map.of("width", 5.0, "length", 4.0, "cutWidth", 5.0, "cutLength", 2.0)))
                .isEqualTo(RoomTemplates.ERR_CUT);
        assertThat(l.validate(Map.of("width", 5.0, "length", 4.0, "cutWidth", 2.0)))
                .isEqualTo(RoomTemplates.ERR_POSITIVE); // missing cutLength reads as 0
        assertThat(RoomTemplates.get(TemplateId.CHAMFERED)
                .validate(Map.of("width", 2.0, "length", 4.0, "chamferSize", 1.0))).isEqualTo(RoomTemplates.ERR_CUT);
        assertThat(RoomTemplates.get(TemplateId.RECTANGLE)
                .validate(Map.of("width", Double.NaN, "length", 3.0))).isEqualTo(RoomTemplates.ERR_POSITIVE);
    }

    @Test
    void rotatedLShapeStartsAtBottomMostLeftMostVertex() {
        List<Pt> v = RoomTemplates.build(TemplateId.L_SHAPE,
                Map.of("width", 5.0, "length", 4.0, "cutWidth", 2.0, "cutLength", 2.0), 1);
        assertThat(v).containsExactly(new Pt(0, 0), new Pt(2, 0), new Pt(2, 2), new Pt(4, 2), new Pt(4, 5),
                new Pt(0, 5));
        assertThat(RoomTemplates.wallCount(TemplateId.T_SHAPE)).isEqualTo(8);
    }
}
