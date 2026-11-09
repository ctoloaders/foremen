package com.foremen.service.dashboard.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.dashboard.appearance.WidgetStyleCatalog;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.config.WidgetConfig.Agg;
import com.foremen.service.dashboard.config.WidgetConfig.Bucket;
import com.foremen.service.dashboard.config.WidgetConfig.Column;
import com.foremen.service.dashboard.config.WidgetConfig.Dimension;
import com.foremen.service.dashboard.config.WidgetConfig.Element;
import com.foremen.service.dashboard.config.WidgetConfig.Filter;
import com.foremen.service.dashboard.config.WidgetConfig.Link;
import com.foremen.service.dashboard.config.WidgetConfig.Localized;
import com.foremen.service.dashboard.config.WidgetConfig.Measure;
import com.foremen.service.dashboard.config.WidgetConfig.Presentation;
import com.foremen.service.dashboard.config.WidgetConfig.Sort;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a raw Widget_Config document against the v1 schema and the dataset field capabilities
 * (FOR-10 Requirements 5.1-5.6, 10.2). Collects ALL violations instead of failing on the first one;
 * the only exception is a structural parse failure (unknown property, wrong JSON type, unknown enum
 * constant), which is reported alone because no typed model exists to inspect further.
 *
 * <p>Not a Spring component on purpose: it is registered as a bean together with the
 * {@link DatasetSchemaLookup} implementation (dataset registry, task 2.3).
 */
public class WidgetConfigValidator {

    /** Maximum serialized size of a config document. */
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_COLUMNS = 12;
    public static final int MAX_COLORS = 8;
    public static final int MAX_MEASURES_CHART = 3;

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,31}$");
    private static final Pattern LITERAL = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final String ROW_PREFIX = "$row.";
    private static final String PROJECT_REF = "$project";

    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    /** One validation failure: JSON-ish {@code path}, stable {@code code}, human {@code message}. */
    public record Violation(String path, String code, String message) {
    }

    /** Result of {@link #validate}: the typed config (null when structurally unparsable) and violations. */
    public record Result(WidgetConfig config, List<Violation> violations) {
        public boolean valid() {
            return violations.isEmpty();
        }
    }

    private final DatasetSchemaLookup datasets;

    public WidgetConfigValidator(DatasetSchemaLookup datasets) {
        this.datasets = datasets;
    }

    /** Convenience: validate a JSON string (size cap measured in UTF-8 bytes). */
    public Result validate(String json, boolean singleProject) {
        if (json == null) {
            return failure("$", "config.required", "Widget_Config is required");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return failure("$", "config.tooLarge", "Widget_Config exceeds " + MAX_BYTES + " bytes");
        }
        try {
            return validate(STRICT.readTree(json), singleProject);
        } catch (JsonProcessingException e) {
            return failure("$", "config.malformed", "Not a valid JSON document");
        }
    }

    /**
     * @param raw           the config document
     * @param singleProject true when the owning widget has project scope SINGLE_PROJECT
     */
    public Result validate(JsonNode raw, boolean singleProject) {
        if (raw == null || raw.isNull() || !raw.isObject()) {
            return failure("$", "config.required", "Widget_Config must be a JSON object");
        }
        try {
            if (STRICT.writeValueAsBytes(raw).length > MAX_BYTES) {
                return failure("$", "config.tooLarge", "Widget_Config exceeds " + MAX_BYTES + " bytes");
            }
        } catch (JsonProcessingException e) {
            return failure("$", "config.malformed", "Not a valid JSON document");
        }
        WidgetConfig cfg;
        try {
            cfg = STRICT.treeToValue(raw, WidgetConfig.class);
        } catch (JsonMappingException e) {
            return failure(pathOf(e), "config.structure", rootMessage(e));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return failure("$", "config.structure", "Config does not match the schema");
        }
        return new Result(cfg, validate(cfg, singleProject));
    }

    /** Semantic validation of an already typed config. */
    public List<Violation> validate(WidgetConfig cfg, boolean singleProject) {
        List<Violation> v = new ArrayList<>();
        if (cfg.schemaVersion() == null || cfg.schemaVersion() != WidgetConfig.SCHEMA_VERSION) {
            v.add(new Violation("schemaVersion", "schemaVersion.unsupported",
                    "Unsupported schemaVersion; expected " + WidgetConfig.SCHEMA_VERSION));
        }
        if (cfg.limit() != null && (cfg.limit() < 1 || cfg.limit() > WidgetConfig.MAX_LIMIT)) {
            v.add(new Violation("limit", "limit.range", "limit must be within 1.." + WidgetConfig.MAX_LIMIT));
        }
        if (cfg.title() != null) {
            checkLocalized(v, "title", cfg.title());
        }
        if (cfg.visualization() == null) {
            v.add(new Violation("visualization", "visualization.required", "visualization is required"));
        }

        DatasetSchema ds = null;
        if (cfg.dataset() == null || cfg.dataset().isBlank()) {
            v.add(new Violation("dataset", "dataset.required", "dataset is required"));
        } else {
            Optional<DatasetSchema> found = datasets.find(cfg.dataset());
            if (found.isEmpty()) {
                v.add(new Violation("dataset", "dataset.unknown", "Unknown dataset: " + cfg.dataset()));
            } else {
                ds = found.get();
            }
        }
        if (singleProject && ds != null && !ds.projectScoped()) {
            v.add(new Violation("dataset", "dataset.noProjectPath",
                    "SINGLE_PROJECT widgets require a dataset with a project path"));
        }

        if (ds != null) {
            checkFields(v, cfg, ds);
        }
        if (cfg.visualization() != null) {
            checkShape(v, cfg);
        }
        checkSort(v, cfg);
        checkLinks(v, cfg, singleProject);
        checkPresentation(v, cfg);
        return v;
    }

    // ------------------------------------------------------------------ fields

    private void checkFields(List<Violation> v, WidgetConfig cfg, DatasetSchema ds) {
        for (int i = 0; i < cfg.columns().size(); i++) {
            Column c = cfg.columns().get(i);
            String p = "columns[" + i + "]";
            requireField(v, p, c.field(), ds, "selectable", DatasetSchema.Field::selectable);
            checkLocalized(v, p + ".label", c.label());
        }
        for (int i = 0; i < cfg.dimensions().size(); i++) {
            Dimension d = cfg.dimensions().get(i);
            String p = "dimensions[" + i + "]";
            Optional<DatasetSchema.Field> f =
                    requireField(v, p, d.field(), ds, "groupable", DatasetSchema.Field::groupable);
            if (d.bucket() != null && f.isPresent() && !isTemporal(f.get().type())) {
                v.add(new Violation(p + ".bucket", "dimension.bucketType",
                        "Date bucket is only allowed on DATE/DATETIME fields"));
            }
            if (d.label() != null) {
                checkLocalized(v, p + ".label", d.label());
            }
        }
        Set<String> aliases = new HashSet<>();
        for (int i = 0; i < cfg.measures().size(); i++) {
            Measure m = cfg.measures().get(i);
            String p = "measures[" + i + "]";
            Optional<DatasetSchema.Field> f =
                    requireField(v, p, m.field(), ds, "aggregatable", DatasetSchema.Field::aggregatable);
            if (m.agg() == null) {
                v.add(new Violation(p + ".agg", "measure.aggRequired", "agg is required"));
            } else if (f.isPresent() && !aggAllowed(m.agg(), f.get().type())) {
                v.add(new Violation(p + ".agg", "measure.aggType",
                        m.agg() + " is not allowed over a " + f.get().type() + " field"));
            }
            if (m.alias() == null || !ALIAS.matcher(m.alias()).matches()) {
                v.add(new Violation(p + ".alias", "measure.alias", "alias must match " + ALIAS.pattern()));
            } else if (!aliases.add(m.alias())) {
                v.add(new Violation(p + ".alias", "measure.aliasDuplicate", "Duplicate alias: " + m.alias()));
            }
            checkLocalized(v, p + ".label", m.label());
        }
        for (int i = 0; i < cfg.filters().size(); i++) {
            checkFilter(v, "filters[" + i + "]", cfg.filters().get(i), ds);
        }
        if (cfg.dateField() != null) {
            Optional<DatasetSchema.Field> f = ds.field(cfg.dateField());
            if (f.isEmpty()) {
                v.add(new Violation("dateField", "field.unknown", "Unknown field: " + cfg.dateField()));
            } else if (!isTemporal(f.get().type())) {
                v.add(new Violation("dateField", "dateField.type", "dateField must be a DATE/DATETIME field"));
            }
        }
    }

    private Optional<DatasetSchema.Field> requireField(List<Violation> v, String path, String name,
                                                       DatasetSchema ds, String capability,
                                                       java.util.function.Predicate<DatasetSchema.Field> cap) {
        if (name == null || name.isBlank()) {
            v.add(new Violation(path + ".field", "field.required", "field is required"));
            return Optional.empty();
        }
        Optional<DatasetSchema.Field> f = ds.field(name);
        if (f.isEmpty()) {
            v.add(new Violation(path + ".field", "field.unknown", "Unknown field: " + name));
        } else if (!cap.test(f.get())) {
            v.add(new Violation(path + ".field", "field.capability",
                    "Field " + name + " is not " + capability));
        }
        return f;
    }

    private void checkFilter(List<Violation> v, String p, Filter f, DatasetSchema ds) {
        Optional<DatasetSchema.Field> field =
                requireField(v, p, f.field(), ds, "filterable", DatasetSchema.Field::filterable);
        if (f.op() == null) {
            v.add(new Violation(p + ".op", "filter.opRequired", "op is required"));
            return;
        }
        JsonNode val = f.value();
        boolean absent = val == null || val.isNull() || val.isMissingNode();
        switch (f.op()) {
            case IS_NULL, IS_NOT_NULL -> {
                if (!absent) {
                    v.add(new Violation(p + ".value", "filter.valueUnexpected", f.op() + " takes no value"));
                }
            }
            case IN -> {
                if (absent || !val.isArray() || val.isEmpty() || !allScalar(val)) {
                    v.add(new Violation(p + ".value", "filter.valueShape",
                            "IN requires a non-empty array of scalars"));
                }
            }
            case BETWEEN -> {
                if (absent || !val.isArray() || val.size() != 2 || !allScalar(val)) {
                    v.add(new Violation(p + ".value", "filter.valueShape",
                            "BETWEEN requires an array of exactly two scalars"));
                }
            }
            default -> {
                if (absent || !val.isValueNode()) {
                    v.add(new Violation(p + ".value", "filter.valueShape", f.op() + " requires a scalar value"));
                }
            }
        }
        boolean ordering = f.op() == WidgetConfig.Op.GT || f.op() == WidgetConfig.Op.GTE
                || f.op() == WidgetConfig.Op.LT || f.op() == WidgetConfig.Op.LTE
                || f.op() == WidgetConfig.Op.BETWEEN;
        if (ordering && field.isPresent()) {
            FieldType t = field.get().type();
            if (t == FieldType.BOOLEAN || t == FieldType.ENUM) {
                v.add(new Violation(p + ".op", "filter.opType", f.op() + " is not allowed over a " + t + " field"));
            }
        }
    }

    private static boolean allScalar(JsonNode arr) {
        for (JsonNode n : arr) {
            if (!n.isValueNode() || n.isNull()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTemporal(FieldType t) {
        return t == FieldType.DATE || t == FieldType.DATETIME;
    }

    private static boolean aggAllowed(Agg agg, FieldType t) {
        return switch (agg) {
            case COUNT, COUNT_DISTINCT -> true;
            case SUM, AVG -> t == FieldType.NUMBER || t == FieldType.MONEY;
            case MIN, MAX -> t == FieldType.NUMBER || t == FieldType.MONEY || isTemporal(t);
        };
    }

    // ------------------------------------------------------------------- shape

    private void checkShape(List<Violation> v, WidgetConfig cfg) {
        Visualization viz = cfg.visualization();
        int cols = cfg.columns().size();
        int dims = cfg.dimensions().size();
        int meas = cfg.measures().size();
        if (cols > MAX_COLUMNS) {
            v.add(new Violation("columns", "shape.columns", "At most " + MAX_COLUMNS + " columns are allowed"));
        }
        switch (viz) {
            case TABLE, LIST -> {
                if (meas == 0) {
                    if (dims > 0) {
                        v.add(new Violation("dimensions", "shape.dimensionsWithoutMeasures",
                                viz + " with dimensions requires measures"));
                    }
                    if (cols < 1) {
                        v.add(new Violation("columns", "shape.columns", viz + " requires 1.." + MAX_COLUMNS + " columns"));
                    }
                } else if (dims < 1) {
                    v.add(new Violation("dimensions", "shape.dimensions",
                            "A grouped " + viz + " requires at least one dimension"));
                }
            }
            case BAR, LINE, AREA -> {
                requireCount(v, "dimensions", dims, 1, 1, viz);
                requireCount(v, "measures", meas, 1, MAX_MEASURES_CHART, viz);
                noColumns(v, cols, viz);
            }
            case PIE, DONUT -> {
                requireCount(v, "dimensions", dims, 1, 1, viz);
                requireCount(v, "measures", meas, 1, 1, viz);
                noColumns(v, cols, viz);
            }
            case KPI -> {
                requireCount(v, "measures", meas, 1, 1, viz);
                requireCount(v, "dimensions", dims, 0, 0, viz);
                noColumns(v, cols, viz);
                Presentation pr = cfg.presentation();
                if (pr != null && Boolean.TRUE.equals(pr.compareToPrevious()) && cfg.dateField() == null) {
                    v.add(new Violation("presentation.compareToPrevious", "shape.compareNeedsDateField",
                            "compareToPrevious requires dateField"));
                }
            }
            case PROJECT_CARD -> {
                if (!"PROJECTS".equals(cfg.dataset())) {
                    v.add(new Violation("dataset", "shape.projectCardDataset", "PROJECT_CARD requires dataset PROJECTS"));
                }
                if (cols < 1) {
                    v.add(new Violation("columns", "shape.columns", "PROJECT_CARD requires 1.." + MAX_COLUMNS + " columns"));
                }
                requireCount(v, "dimensions", dims, 0, 0, viz);
                requireCount(v, "measures", meas, 0, 0, viz);
            }
        }
        if (viz != Visualization.KPI && cfg.presentation() != null
                && Boolean.TRUE.equals(cfg.presentation().compareToPrevious())) {
            v.add(new Violation("presentation.compareToPrevious", "shape.compareOnlyKpi",
                    "compareToPrevious is only valid for KPI"));
        }
    }

    private static void requireCount(List<Violation> v, String section, int actual, int min, int max,
                                     Visualization viz) {
        if (actual < min || actual > max) {
            String expected = min == max ? "exactly " + min : min + ".." + max;
            v.add(new Violation(section, "shape." + section,
                    viz + " requires " + expected + " " + section + " (found " + actual + ")"));
        }
    }

    private static void noColumns(List<Violation> v, int cols, Visualization viz) {
        if (cols > 0) {
            v.add(new Violation("columns", "shape.columns", viz + " does not accept columns"));
        }
    }

    // --------------------------------------------------------------- sort/links

    private void checkSort(List<Violation> v, WidgetConfig cfg) {
        Set<String> sortable = new HashSet<>();
        cfg.measures().forEach(m -> sortable.add(m.alias()));
        cfg.dimensions().forEach(d -> sortable.add(d.field()));
        cfg.columns().forEach(c -> sortable.add(c.field()));
        for (int i = 0; i < cfg.sort().size(); i++) {
            Sort s = cfg.sort().get(i);
            if (s.by() == null || !sortable.contains(s.by())) {
                v.add(new Violation("sort[" + i + "].by", "sort.unknown",
                        "sort.by must reference a selected column, dimension or measure alias"));
            }
            if (s.dir() == null) {
                v.add(new Violation("sort[" + i + "].dir", "sort.dirRequired", "dir is required"));
            }
        }
    }

    private void checkLinks(List<Violation> v, WidgetConfig cfg, boolean singleProject) {
        Set<String> rowRefs = new HashSet<>();
        cfg.columns().forEach(c -> rowRefs.add(c.field()));
        cfg.dimensions().forEach(d -> rowRefs.add(d.field()));
        cfg.measures().forEach(m -> rowRefs.add(m.alias()));
        for (int i = 0; i < cfg.links().size(); i++) {
            Link l = cfg.links().get(i);
            String p = "links[" + i + "]";
            if (l.element() == null) {
                v.add(new Violation(p + ".element", "link.elementRequired", "element is required"));
            } else {
                checkElement(v, p, l, cfg);
            }
            checkRoute(v, p, l, rowRefs, singleProject);
        }
    }

    private void checkElement(List<Violation> v, String p, Link l, WidgetConfig cfg) {
        Visualization viz = cfg.visualization();
        Element e = l.element();
        if (viz != null) {
            boolean ok = switch (e) {
                case ROW -> viz == Visualization.TABLE || viz == Visualization.LIST || viz == Visualization.PROJECT_CARD;
                case CELL -> viz == Visualization.TABLE || viz == Visualization.LIST;
                case CHART_SEGMENT -> viz == Visualization.BAR || viz == Visualization.LINE
                        || viz == Visualization.AREA || viz == Visualization.PIE || viz == Visualization.DONUT;
                case KPI -> viz == Visualization.KPI;
                case CARD_TITLE, CARD_CHIP -> viz == Visualization.PROJECT_CARD;
            };
            if (!ok) {
                v.add(new Violation(p + ".element", "link.elementViz", e + " links are not valid for " + viz));
            }
        }
        if (e == Element.CELL) {
            boolean known = l.column() != null
                    && (cfg.columns().stream().anyMatch(c -> l.column().equals(c.field()))
                    || cfg.dimensions().stream().anyMatch(d -> l.column().equals(d.field())));
            if (!known) {
                v.add(new Violation(p + ".column", "link.columnUnknown",
                        "CELL link column must be a selected column or dimension"));
            }
        }
        if (e == Element.CARD_CHIP) {
            checkLocalized(v, p + ".label", l.label());
        }
    }

    private void checkRoute(List<Violation> v, String p, Link l, Set<String> rowRefs, boolean singleProject) {
        if (!RouteWhitelist.isAllowed(l.route())) {
            v.add(new Violation(p + ".route", "link.routeNotAllowed",
                    "route is not in the whitelist"));
            return;
        }
        List<String> placeholders = RouteWhitelist.placeholders(l.route());
        for (String ph : placeholders) {
            if (!l.params().containsKey(ph)) {
                v.add(new Violation(p + ".params", "link.paramUnbound", "Placeholder {" + ph + "} is not bound"));
            }
        }
        for (Map.Entry<String, String> en : l.params().entrySet()) {
            String name = en.getKey();
            String pp = p + ".params." + name;
            if (!placeholders.contains(name)) {
                v.add(new Violation(pp, "link.paramUnknown", "No such placeholder in route: " + name));
                continue;
            }
            String val = en.getValue();
            if (val == null || val.isBlank()) {
                v.add(new Violation(pp, "link.paramValue", "Param value is required"));
            } else if (val.equals(PROJECT_REF)) {
                if (!singleProject || !"projectId".equals(name)) {
                    v.add(new Violation(pp, "link.projectRef",
                            "$project is only valid for projectId in a SINGLE_PROJECT widget"));
                }
            } else if (val.startsWith(ROW_PREFIX)) {
                if (singleProject && "projectId".equals(name)) {
                    v.add(new Violation(pp, "link.projectContradiction",
                            "projectId of a SINGLE_PROJECT widget must be $project"));
                } else if (!rowRefs.contains(val.substring(ROW_PREFIX.length()))) {
                    v.add(new Violation(pp, "link.rowRefNotSelected",
                            "$row reference must be a selected column, dimension or measure alias"));
                }
            } else if (singleProject && "projectId".equals(name)) {
                v.add(new Violation(pp, "link.projectContradiction",
                        "projectId of a SINGLE_PROJECT widget must be $project"));
            } else if (!LITERAL.matcher(val).matches()) {
                v.add(new Violation(pp, "link.paramValue", "Literal param value is malformed"));
            } else if ("tab".equals(name) && !RouteWhitelist.PROJECT_TABS.contains(val)) {
                v.add(new Violation(pp, "link.tabUnknown", "Unknown project tab: " + val));
            }
        }
    }

    // ------------------------------------------------------------ presentation

    private void checkPresentation(List<Violation> v, WidgetConfig cfg) {
        Presentation pr = cfg.presentation();
        if (pr == null) {
            return;
        }
        if (pr.style() != null && cfg.visualization() != null
                && !WidgetStyleCatalog.isAllowed(cfg.visualization(), pr.style())) {
            v.add(new Violation("presentation.style", "presentation.style",
                    "Style " + pr.style() + " is not in the " + cfg.visualization() + " catalog"));
        }
        if (pr.colors().size() > MAX_COLORS) {
            v.add(new Violation("presentation.colors", "presentation.colors",
                    "At most " + MAX_COLORS + " colors are allowed"));
        }
        for (int i = 0; i < pr.colors().size(); i++) {
            String c = pr.colors().get(i);
            if (c == null || !HEX_COLOR.matcher(c).matches()) {
                v.add(new Violation("presentation.colors[" + i + "]", "presentation.colors",
                        "Color must match #RRGGBB"));
            }
        }
        if (pr.emptyText() != null) {
            checkLocalized(v, "presentation.emptyText", pr.emptyText());
        }
    }

    // ----------------------------------------------------------------- helpers

    private static void checkLocalized(List<Violation> v, String path, Localized l) {
        if (l == null || l.ru() == null || l.ru().isBlank() || l.pl() == null || l.pl().isBlank()) {
            v.add(new Violation(path, "localized.missing", "Both ru and pl strings are required"));
        }
    }

    private static Result failure(String path, String code, String message) {
        return new Result(null, List.of(new Violation(path, code, message)));
    }

    private static String pathOf(JsonMappingException e) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference r : e.getPath()) {
            if (r.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                sb.append('[').append(r.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? "$" : sb.toString();
    }

    private static String rootMessage(JsonMappingException e) {
        String m = e.getOriginalMessage();
        return m == null ? "Config does not match the schema" : m;
    }
}
