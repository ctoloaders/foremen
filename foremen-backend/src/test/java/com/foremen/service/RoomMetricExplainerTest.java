package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.MeasureSource;
import com.foremen.service.model.MetricExplanation;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FOR-05-02 Round 11 #3: formula + resolved variable values of the CALCULATED metrics. */
class RoomMetricExplainerTest {

    private static RoomMetricExplainer.Input flat(MeasureSource wall, MeasureSource door, MeasureSource window) {
        return new RoomMetricExplainer.Input(null, new BigDecimal("2.5"),
                2, new BigDecimal("2.1"), new BigDecimal("0.9"),
                null, null, null,
                new BigDecimal("1.5"), new BigDecimal("0.5"), new BigDecimal("14.00"), new BigDecimal("3.78"), null,
                wall, door, window);
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
