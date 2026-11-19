package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.service.model.MetricExplanation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FOR-05-02 Round 11 #3: formula + resolved variable values of the CALCULATED metrics. */
class RoomMetricExplainerTest {

    private static RoomMetricExplainer.Input flat(MeasureSource wall, MeasureSource door, MeasureSource window) {
        return new RoomMetricExplainer.Input(null, new BigDecimal("2.5"),
                2, new BigDecimal("2.1"), new BigDecimal("0.9"),
                null, null, null,
                new BigDecimal("1.5"), new BigDecimal("0.5"), new BigDecimal("14.00"), new BigDecimal("3.78"), null,
                wall, door, window, null, null);
    }

    private static RoomGeometry.Vertex vertex(double x, double y) {
        RoomGeometry.Vertex v = new RoomGeometry.Vertex();
        v.setX(x);
        v.setY(y);
        return v;
    }

    private static RoomMetricExplainer.Input rect(MeasureSource perimeterSource, Map<String, String> formulas) {
        RoomGeometry g = new RoomGeometry();
        g.setVertices(List.of(vertex(0, 0), vertex(4, 0), vertex(4, 3), vertex(0, 3)));
        return new RoomMetricExplainer.Input(g, new BigDecimal("2.5"), null, null, null, null, null, null,
                null, null, new BigDecimal("14.00"), null, null, null, null, null, perimeterSource, formulas);
    }

    @Test
    void explainsPerimeterAsSumOfWalls() {
        MetricExplanation p = RoomMetricExplainer.explain(rect(MeasureSource.CALCULATED, null)).get("perimeter");
        assertThat(p.formula()).isEqualTo("wall1 + wall2 + wall3 + wall4");
        assertThat(p.variables()).containsEntry("wall1", new BigDecimal("4.00"))
                .containsEntry("wall2", new BigDecimal("3.00"))
                .containsEntry("wall3", new BigDecimal("4.00"))
                .containsEntry("wall4", new BigDecimal("3.00"));
    }

    @Test
    void wallLengthFormulaReplacesWallName() {
        MetricExplanation p = RoomMetricExplainer.explain(
                rect(MeasureSource.CALCULATED, Map.of("geometry.walls[1].length", "=2*1.5"))).get("perimeter");
        assertThat(p.formula()).isEqualTo("wall1 + 2*1.5 + wall3 + wall4");
        assertThat(p.variables()).containsEntry("2*1.5", new BigDecimal("3.00")).doesNotContainKey("wall2");
    }

    @Test
    void manualPerimeterIsNotExplained() {
        assertThat(RoomMetricExplainer.explain(rect(MeasureSource.MANUAL, null))).isNull();
    }

    @Test
    void explainsWallAreaWithResolvedVariables() {
        Map<String, MetricExplanation> out = RoomMetricExplainer.explain(
                flat(MeasureSource.CALCULATED, null, null));
        MetricExplanation wall = out.get("wallArea");
        assertThat(wall.formula()).isEqualTo("(perimeter - finishGap - wallGap) * ceilingHeight - doorArea - windowArea");
        assertThat(wall.variables()).containsEntry("perimeter", new BigDecimal("14.00"))
                .containsEntry("finishGap", new BigDecimal("1.5"))
                .containsEntry("wallGap", new BigDecimal("0.5"))
                .containsEntry("ceilingHeight", new BigDecimal("2.5"))
                .containsEntry("doorArea", new BigDecimal("3.78"))
                .containsEntry("windowArea", BigDecimal.ZERO); // absent -> 0, as in the calculation
    }

    @Test
    void explainsFlatDoorAreaFromCountHeightWidth() {
        MetricExplanation door = RoomMetricExplainer.explain(
                flat(null, MeasureSource.CALCULATED, null)).get("doorArea");
        assertThat(door.formula()).isEqualTo("doorCount * doorHeight * doorWidth");
        assertThat(door.variables()).containsEntry("doorCount", BigDecimal.valueOf(2))
                .containsEntry("doorHeight", new BigDecimal("2.1"))
                .containsEntry("doorWidth", new BigDecimal("0.9"));
    }

    @Test
    void manualMetricsAreNotExplained() {
        assertThat(RoomMetricExplainer.explain(flat(MeasureSource.MANUAL, MeasureSource.MANUAL, null))).isNull();
    }
}
