package com.foremen.service.dashboard.ai;

import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfig.Agg;
import com.foremen.service.dashboard.config.WidgetConfig.Bucket;
import com.foremen.service.dashboard.config.WidgetConfig.Dir;
import com.foremen.service.dashboard.config.WidgetConfig.Element;
import com.foremen.service.dashboard.config.WidgetConfig.Format;
import com.foremen.service.dashboard.config.WidgetConfig.Op;
import com.foremen.service.dashboard.config.WidgetConfig.RowScope;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto.FieldDto;
import com.foremen.service.dashboard.GridMath;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Response JSON schema (JSON-Schema subset accepted by Gemini structured output) of a widget draft.
 * The {@code config} part mirrors {@link WidgetConfig}; dataset codes and field names are restricted
 * to the enum of the catalog the administrator may use, so the model cannot invent them. Semantic
 * rules (capabilities, shapes, routes) are enforced afterwards by the config validator.
 */
public final class WidgetDraftSchemas {

    private WidgetDraftSchemas() {
    }

    /** Schema of {@link DraftResponse} for the given catalog. */
    public static Map<String, Object> draft(List<DatasetCatalogDto> catalog, List<String> routes) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", "string");
        scope.put("enum", List.of(DraftResponse.SCOPE_NONE, DraftResponse.SCOPE_SINGLE_PROJECT));
        return obj(props(
                "names", localized(),
                "descriptions", localized(),
                "colSpan", range("integer", 1, GridMath.COLS),
                "rowSpan", range("integer", 1, GridMath.ROWS),
                "projectScope", scope,
                "config", config(catalog, routes),
                "explanation", localized()),
                List.of("names", "descriptions", "colSpan", "rowSpan", "projectScope", "config", "explanation"));
    }

    /** Schema of {@link WidgetConfig} (schema version 1). */
    public static Map<String, Object> config(List<DatasetCatalogDto> catalog, List<String> routes) {
        Map<String, Object> dataset = str();
        List<String> codes = catalog.stream().map(DatasetCatalogDto::code).sorted().toList();
        if (!codes.isEmpty()) {
            dataset = enumOf(codes);
        }
        Map<String, Object> field = str();
        TreeSet<String> names = new TreeSet<>();
        for (DatasetCatalogDto d : catalog) {
            for (FieldDto f : d.fields()) {
                names.add(f.name());
            }
        }
        if (!names.isEmpty()) {
            field = enumOf(List.copyOf(names));
        }
        Map<String, Object> route = routes.isEmpty() ? str() : enumOf(routes.stream().sorted().toList());

        Map<String, Object> column = obj(props("field", field, "label", localized(),
                "format", enumOf(names(Format.values()))), List.of("field", "label"));
        Map<String, Object> dimension = obj(props("field", field, "bucket", enumOf(names(Bucket.values())),
                "label", localized()), List.of("field"));
        Map<String, Object> measure = obj(props("field", field, "agg", enumOf(names(Agg.values())),
                "alias", str(), "label", localized(), "format", enumOf(names(Format.values()))),
                List.of("field", "agg", "alias", "label"));
        Map<String, Object> scalar = Map.of("anyOf", List.of(
                Map.of("type", "string"), Map.of("type", "number"), Map.of("type", "boolean")));
        Map<String, Object> filterValue = Map.of("anyOf", List.of(
                Map.of("type", "string"), Map.of("type", "number"), Map.of("type", "boolean"),
                Map.of("type", "array", "items", scalar)),
                "description", "Scalar, or an array for IN / BETWEEN (exactly two elements); omit for IS_NULL");
        Map<String, Object> filter = obj(props("field", field, "op", enumOf(names(Op.values())),
                "value", filterValue), List.of("field", "op"));
        Map<String, Object> sort = obj(props("by", str(), "dir", enumOf(names(Dir.values()))),
                List.of("by", "dir"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("additionalProperties", Map.of("type", "string"));
        params.put("description", "Placeholder name -> $row.<field>, $project or a literal");
        Map<String, Object> link = obj(props("element", enumOf(names(Element.values())), "column", str(),
                "label", localized(), "route", route, "params", params), List.of("element", "route", "params"));
        Map<String, Object> presentation = obj(props("style", str(),
                "colors", array(str()), "stacked", bool(), "legend", bool(),
                "emptyText", localized(), "compareToPrevious", bool()), List.of());

        return obj(props(
                "schemaVersion", Map.of("type", "integer", "enum", List.of(WidgetConfig.SCHEMA_VERSION)),
                "dataset", dataset,
                "visualization", enumOf(names(Visualization.values())),
                "title", localized(),
                "columns", array(column),
                "dimensions", array(dimension),
                "measures", array(measure),
                "filters", array(filter),
                "dateField", field,
                "rowScope", enumOf(names(RowScope.values())),
                "sort", array(sort),
                "limit", range("integer", 1, WidgetConfig.MAX_LIMIT),
                "links", array(link),
                "presentation", presentation),
                List.of("schemaVersion", "dataset", "visualization", "title"));
    }

    private static Map<String, Object> localized() {
        return obj(props("ru", str(), "pl", str()), List.of("ru", "pl"));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static Map<String, Object> enumOf(List<String> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("enum", values);
        return m;
    }

    private static Map<String, Object> range(String type, int min, int max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("minimum", min);
        m.put("maximum", max);
        return m;
    }

    private static Map<String, Object> obj(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        if (!required.isEmpty()) {
            m.put("required", required);
        }
        return m;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }
}
