package com.foremen.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.mapper.RoomServiceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * FOR-05-02c bug condition exploration (Property 1): manual {@code wallGap}/{@code finishGap} and
 * door/window sizes are lost or not derived on the geometry path of {@code RoomService}.
 *
 * <p>This test encodes the EXPECTED (fixed) behavior and is meant to FAIL on unfixed code. The six
 * new {@code *Source} properties do not exist on the unfixed model, so the save input is built from
 * a JSON map through {@link ObjectMapper} (unknown properties ignored) and the sources are read
 * back through a map view of the model. No new getters/setters are referenced directly.
 *
 * <p>Scenario (room 1701 "Przedpokój"): rectangle 3.0 x 2.11 (perimeter 10.22), ceiling 2.7, one DOOR
 * 0.58 x 2.0 (area 1.16), {@code wallGap = 2}, {@code finishGap = 3}, per-wall gaps {@code null}.
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.5, 2.6, 2.7</b>
 */
class RoomManualDimensionsExplorationTest {

    private static final BigDecimal CEILING = new BigDecimal("2.7");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RoomService roomService = buildService();

    // --- Room 1701 reproduction: manual gaps must survive and feed wallArea ---

    @Test
    void manualGapsAreKeptOnGeometryRoom() {
        Map<String, Object> input = room1701Input(List.of(opening("DOOR", 1, 2.0, 0.58)));
        input.put("wallGap", 2);
        input.put("finishGap", 3);
        input.put("wallGapSource", "MANUAL");
        input.put("finishGapSource", "MANUAL");

        RoomServiceExtendedModel saved = save(input);

        assertThat(saved.getWallGap()).as("wallGap").isEqualByComparingTo("2");
        assertThat(saved.getFinishGap()).as("finishGap").isEqualByComparingTo("3");
        assertThat(view(saved).get("wallGapSource")).as("wallGapSource").isEqualTo("MANUAL");
        assertThat(view(saved).get("finishGapSource")).as("finishGapSource").isEqualTo("MANUAL");
    }

    @Test
    void wallAreaIsComputedFromManualGaps() {
        Map<String, Object> input = room1701Input(List.of(opening("DOOR", 1, 2.0, 0.58)));
        input.put("wallGap", 2);
        input.put("finishGap", 3);
        input.put("wallGapSource", "MANUAL");
        input.put("finishGapSource", "MANUAL");

        RoomServiceExtendedModel saved = save(input);

        // (10.22 - 3 - 2) * 2.7 - 1.16 = 12.934 -> 12.93 (the value shown on screen before the save)
        BigDecimal expected = new BigDecimal("10.22").subtract(new BigDecimal("5")).multiply(CEILING)
                .subtract(new BigDecimal("1.16")).setScale(2, RoundingMode.HALF_UP);
        assertThat(expected).isEqualByComparingTo("12.93");
        assertThat(saved.getWallArea()).as("wallArea").isEqualByComparingTo(expected);
    }

    @Test
    void manualGapsSurviveReSave() {
        Map<String, Object> input = room1701Input(List.of(opening("DOOR", 1, 2.0, 0.58)));
        input.put("wallGap", 2);
        input.put("finishGap", 3);
        input.put("wallGapSource", "MANUAL");
        input.put("finishGapSource", "MANUAL");

        RoomServiceExtendedModel first = save(input);
        // "Reload": feed the persisted state (values + sources) back as the next save request.
        RoomServiceExtendedModel second = save(toMap(first));

        assertThat(second.getWallGap()).as("wallGap after reload").isEqualByComparingTo("2");
        assertThat(second.getFinishGap()).as("finishGap after reload").isEqualByComparingTo("3");
        assertThat(second.getWallArea()).as("wallArea after reload").isEqualByComparingTo("12.93");
    }

    // --- Opening size derived from geometry ---

    @Test
    void singleDoorSizeIsDerivedFromGeometry() {
        Map<String, Object> input = room1701Input(List.of(opening("DOOR", 1, 2.0, 0.58)));

        RoomServiceExtendedModel saved = save(input);

        assertThat(saved.getDoorHeight()).as("doorHeight").isNotNull().isEqualByComparingTo("2.0");
        assertThat(saved.getDoorWidth()).as("doorWidth").isNotNull().isEqualByComparingTo("0.58");
        assertThat(view(saved).get("doorHeightSource")).as("doorHeightSource").isEqualTo("CALCULATED");
    }

    @Test
    void severalDoorsGiveNullSizesAndSummedArea() {
        Map<String, Object> input = room1701Input(List.of(
                opening("DOOR", 1, 2.0, 0.58),
                opening("DOOR", 1, 2.1, 0.9)));

        RoomServiceExtendedModel saved = save(input);

        assertThat(saved.getDoorHeight()).as("doorHeight").isNull();
        assertThat(saved.getDoorWidth()).as("doorWidth").isNull();
        // 2.0*0.58 + 2.1*0.9 = 1.16 + 1.89 = 3.05
        assertThat(saved.getDoorArea()).as("doorArea").isEqualByComparingTo("3.05");
        assertThat(view(saved).get("doorHeightSource")).as("doorHeightSource").isEqualTo("CALCULATED");
    }

    @Test
    void oneOpeningWithCountTwoGivesNullSizes() {
        Map<String, Object> input = room1701Input(List.of(opening("DOOR", 2, 2.0, 0.58)));

        RoomServiceExtendedModel saved = save(input);

        assertThat(saved.getDoorHeight()).as("doorHeight").isNull();
        assertThat(saved.getDoorWidth()).as("doorWidth").isNull();
        assertThat(saved.getDoorArea()).as("doorArea").isEqualByComparingTo("2.32");
    }

    // --- Manual size survives a second save ---

    @Test
    void manualDoorHeightSurvivesSecondSave() {
        Map<String, Object> input = room1701Input(List.of(
                opening("DOOR", 1, 2.0, 0.58),
                opening("DOOR", 1, 2.1, 0.9)));
        input.put("doorHeight", "2.1");
        input.put("doorHeightSource", "MANUAL");

        RoomServiceExtendedModel first = save(input);
        assertThat(first.getDoorHeight()).as("doorHeight after first save").isEqualByComparingTo("2.1");
        assertThat(view(first).get("doorHeightSource")).as("doorHeightSource after first save")
                .isEqualTo("MANUAL");

        RoomServiceExtendedModel second = save(toMap(first));
        assertThat(second.getDoorHeight()).as("doorHeight after second save").isEqualByComparingTo("2.1");
        assertThat(view(second).get("doorHeightSource")).as("doorHeightSource after second save")
                .isEqualTo("MANUAL");
    }

    // --- Helpers ---

    /** Rectangle 3.0 x 2.11 (perimeter 10.22), ceiling 2.7, all per-wall gaps null, openings on wall 0. */
    private Map<String, Object> room1701Input(List<Map<String, Object>> openings) {
        List<Map<String, Object>> vertices = new ArrayList<>();
        vertices.add(vertex(0.0, 0.0));
        vertices.add(vertex(3.0, 0.0));
        vertices.add(vertex(3.0, 2.11));
        vertices.add(vertex(0.0, 2.11));

        List<Map<String, Object>> walls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> wall = new LinkedHashMap<>();
            wall.put("wallGap", null);
            wall.put("finishGap", null);
            wall.put("openings", i == 0 ? openings : List.of());
            walls.add(wall);
        }

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("vertices", vertices);
        geometry.put("walls", walls);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", 1531);
        input.put("roomTypeId", 2);
        input.put("label", "Przedpokój");
        input.put("ceilingHeight", 2.7);
        input.put("geometry", geometry);
        return input;
    }

    private Map<String, Object> vertex(double x, double y) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("x", x);
        v.put("y", y);
        return v;
    }

    private Map<String, Object> opening(String type, int count, double height, double width) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", type);
        o.put("count", count);
        o.put("height", height);
        o.put("width", width);
        return o;
    }

    /** Builds the model from a JSON-like map (unknown properties ignored) and runs the update hook. */
    private RoomServiceExtendedModel save(Map<String, Object> input) {
        RoomServiceExtendedModel model = mapper.convertValue(input, RoomServiceExtendedModel.class);
        roomService.validateUpdate(null, model);
        return model;
    }

    /** Map view of a model, including any *Source properties the model has. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> view(RoomServiceExtendedModel model) {
        return mapper.convertValue(model, Map.class);
    }

    /** The persisted state fed back as the next save request ("reload then save again"). */
    private Map<String, Object> toMap(RoomServiceExtendedModel model) {
        return new LinkedHashMap<>(view(model));
    }

    private static RoomService buildService() {
        ProjectDao projectDao = Mockito.mock(ProjectDao.class);
        RoomTypeDao roomTypeDao = Mockito.mock(RoomTypeDao.class);
        when(projectDao.findById(anyLong())).thenReturn(Optional.of(new ProjectEntity()));
        when(roomTypeDao.findById(anyLong())).thenReturn(Optional.of(new RoomTypeEntity()));
        return new RoomService(
                Mockito.mock(RoomDao.class),
                Mockito.mock(RoomServiceMapper.class),
                Mockito.mock(ProjectAccessCache.class),
                Mockito.mock(AuditLogDao.class),
                Mockito.mock(EntityManager.class),
                projectDao,
                roomTypeDao,
                new RoomCalculationService());
    }
}
