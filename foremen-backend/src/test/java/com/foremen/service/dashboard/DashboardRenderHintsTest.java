package com.foremen.service.dashboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardRenderHintsTest {

    @Test
    @DisplayName("render hints keep links, presentation, title and column formats only")
    void whitelist() {
        Map<String, Object> cfg = Map.of(
                "dataset", "PURCHASE_POSITIONS",
                "visualization", "TABLE",
                "title", Map.of("ru", "A", "pl", "B"),
                "columns", List.of(Map.of("field", "status", "format", "STATUS", "label", Map.of("ru", "S"))),
                "filters", List.of(Map.of("field", "status", "op", "EQ", "value", "SECRET")),
                "measures", List.of(Map.of("field", "id", "agg", "COUNT", "alias", "cnt")),
                "sort", List.of(Map.of("by", "x", "dir", "ASC")),
                "links", List.of(Map.of("element", "ROW", "route", "/deliveries")),
                "presentation", Map.of("emptyText", Map.of("ru", "-", "pl", "-")));

        Map<String, Object> out = DashboardLayoutService.renderHints(cfg);

        assertThat(out).containsOnlyKeys("title", "columns", "links", "presentation");
        assertThat(out.toString()).doesNotContain("PURCHASE_POSITIONS", "SECRET", "filters", "dataset");
        assertThat(out.get("columns")).isEqualTo(List.of(Map.of("field", "status", "format", "STATUS")));
    }

    @Test
    @DisplayName("null config gives null hints")
    void nullConfig() {
        assertThat(DashboardLayoutService.renderHints(null)).isNull();
    }
}
