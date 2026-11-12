package com.foremen.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.DashboardWidgetEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit and property tests for {@link ThumbnailShapeFactory} / {@link ThumbnailShape}, plus the message-bundle
 * assertion for the layout error codes.
 * <b>Feature: FOR-10 dashboard, Property 23: Thumbnail shape exposes only the whitelisted structure</b>
 * <b>Validates: Requirements 22.1, 22.2, 22.13</b>
 */
class ThumbnailShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KEYS = Set.of("visualization", "style", "columns", "dimensions",
            "measures", "temporal", "stacked", "compareToPrevious");

    private static DashboardWidgetEntity widget(Map<String, Object> config) {
        DashboardWidgetEntity w = new DashboardWidgetEntity();
        w.setCode("SECRET_CODE_X");
        w.setNameRu("СекретноеИмя");
        w.setNamePl("SekretnaNazwa");
        w.setColSpan((short) 2);
        w.setRowSpan((short) 2);
        w.setConfig(config);
        return w;
    }

    private static Set<String> keysOf(ThumbnailShape shape) {
        Set<String> keys = new HashSet<>();
        MAPPER.valueToTree(shape).fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static Map<String, Object> richConfig(String dataset, String label, String filterValue, String field) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("schemaVersion", 1);
        cfg.put("dataset", dataset);
        cfg.put("visualization", "BAR");
        cfg.put("title", Map.of("ru", label, "pl", label));
        cfg.put("dimensions", List.of(Map.of("field", field, "bucket", "MONTH")));
        cfg.put("measures", List.of(Map.of("field", field, "agg", "SUM", "alias", "a",
                "label", Map.of("ru", label, "pl", label)),
                Map.of("field", field, "agg", "COUNT", "alias", "b")));
        cfg.put("filters", List.of(Map.of("field", field, "op", "EQ", "value", filterValue)));
        cfg.put("links", List.of(Map.of("element", "CHART_SEGMENT", "route", "/users")));
        cfg.put("presentation", Map.of("style", "OUTLINED", "stacked", true, "compareToPrevious", true,
                "colors", List.of("#112233")));
        return cfg;
    }

    @Test
    void shapeHasExactKeySetAndExpectedValues() {
        ThumbnailShape shape = ThumbnailShapeFactory.from(
                widget(richConfig("PAYROLL", "Label", "VALUE", "amount")));

        assertThat(keysOf(shape)).isEqualTo(KEYS);
        assertThat(shape).isEqualTo(new ThumbnailShape("BAR", "OUTLINED", 0, 1, 2, true, true, true));
    }

    @Test
    void serializedJsonContainsNoNamesLabelsFiltersDatasetOrConfigValues() throws Exception {
        DashboardWidgetEntity w = widget(richConfig("DATASET_ZZ", "LabelQQ", "FilterVV", "fieldXX"));
        String json = MAPPER.writeValueAsString(ThumbnailShapeFactory.from(w));

        assertThat(json).doesNotContain("DATASET_ZZ", "LabelQQ", "FilterVV", "fieldXX", "SECRET_CODE_X",
                "СекретноеИмя", "SekretnaNazwa", "#112233", "/users", "alias");
    }

    @Test
    void malformedOrMissingConfigGivesZerosAndFalse() {
        ThumbnailShape empty = new ThumbnailShape(null, null, 0, 0, 0, false, false, false);
        assertThat(ThumbnailShapeFactory.from(widget(null))).isEqualTo(empty);
        assertThat(ThumbnailShapeFactory.from(null)).isEqualTo(empty);

        Map<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("dimensions", "not-a-list");
        malformed.put("measures", 42);
        malformed.put("columns", Map.of("a", 1));
        malformed.put("presentation", "oops");
        ThumbnailShape shape = ThumbnailShapeFactory.from(widget(malformed));
        assertThat(shape.columns()).isZero();
        assertThat(shape.dimensions()).isZero();
        assertThat(shape.measures()).isZero();
        assertThat(shape.temporal()).isFalse();
        assertThat(shape.stacked()).isFalse();
        assertThat(shape.compareToPrevious()).isFalse();

        Map<String, Object> vizOnly = new LinkedHashMap<>();
        vizOnly.put("visualization", "KPI");
        assertThat(ThumbnailShapeFactory.from(widget(vizOnly)).visualization()).isEqualTo("KPI");
    }

    @Test
    void shapeIsIdenticalRegardlessOfCallerAccess() {
        // The factory takes only the stored widget: no user/access parameter exists, so equal widgets give equal shapes.
        DashboardWidgetEntity a = widget(richConfig("PAYROLL", "L", "V", "amount"));
        DashboardWidgetEntity b = widget(richConfig("PAYROLL", "L", "V", "amount"));
        assertThat(ThumbnailShapeFactory.from(a)).isEqualTo(ThumbnailShapeFactory.from(b));
    }

    @Property(tries = 100)
    void property23_shapeExposesOnlyWhitelistedStructure(
            @ForAll("secrets") String dataset, @ForAll("secrets") String label,
            @ForAll("secrets") String filter, @ForAll("secrets") String field) throws Exception {
        DashboardWidgetEntity w = widget(richConfig(dataset, label, filter, field));
        ThumbnailShape shape = ThumbnailShapeFactory.from(w);
        JsonNode node = MAPPER.valueToTree(shape);

        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isEqualTo(KEYS);
        String json = MAPPER.writeValueAsString(shape);
        for (String secret : List.of(dataset, label, filter, field)) {
            assertThat(json).doesNotContain(secret);
        }
        assertThat(ThumbnailShapeFactory.from(w)).isEqualTo(shape);
    }

    @Provide
    Arbitrary<String> secrets() {
        // Prefixed so they cannot collide with key names or catalog values in the JSON.
        return Arbitraries.strings().alpha().ofMinLength(6).ofMaxLength(12).map(s -> "zz9" + s + "9zz");
    }

    @Test
    void layoutErrorCodesExistInBothBundles() throws Exception {
        List<String> codes = new ArrayList<>(List.of("error.dashboard.cell.invalid",
                "error.dashboard.cell.occupied", "error.dashboard.forbidden"));
        for (String bundle : List.of("messages.properties", "messages_ru.properties")) {
            Properties props = new Properties();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(bundle)) {
                assertThat(in).as(bundle).isNotNull();
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
            for (String code : codes) {
                assertThat(props.getProperty(code)).as(bundle + " " + code).isNotBlank();
            }
        }
    }
}
