package com.foremen.service.dashboard.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Versioned, declarative widget configuration (FOR-10 Requirement 5, schema v1). Pure data: all
 * semantic checks live in {@link WidgetConfigValidator}. List sections are never {@code null}.
 */
public record WidgetConfig(
        Integer schemaVersion,
        String dataset,
        Visualization visualization,
        Localized title,
        List<Column> columns,
        List<Dimension> dimensions,
        List<Measure> measures,
        List<Filter> filters,
        String dateField,
        RowScope rowScope,
        List<Sort> sort,
        Integer limit,
        List<Link> links,
        Presentation presentation) {

    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public WidgetConfig {
        columns = columns == null ? List.of() : List.copyOf(columns);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        measures = measures == null ? List.of() : List.copyOf(measures);
        filters = filters == null ? List.of() : List.copyOf(filters);
        sort = sort == null ? List.of() : List.copyOf(sort);
        links = links == null ? List.of() : List.copyOf(links);
    }

    public int effectiveLimit() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    public RowScope effectiveRowScope() {
        return rowScope == null ? RowScope.PROJECTS : rowScope;
    }

    public enum Visualization { TABLE, BAR, LINE, AREA, PIE, DONUT, KPI, LIST, PROJECT_CARD }

    public enum RowScope { PROJECTS, OWN }

    public enum Format { TEXT, NUMBER, CURRENCY, PERCENT, DATE, STATUS }

    public enum Bucket { DAY, WEEK, MONTH, YEAR }

    public enum Agg { COUNT, COUNT_DISTINCT, SUM, AVG, MIN, MAX }

    public enum Op { EQ, NE, IN, GT, GTE, LT, LTE, BETWEEN, IS_NULL, IS_NOT_NULL }

    public enum Dir { ASC, DESC }

    public enum Element { ROW, CELL, CHART_SEGMENT, KPI, CARD_TITLE, CARD_CHIP }

    /** Localized user-visible string. */
    public record Localized(String ru, String pl) {
    }

    public record Column(String field, Localized label, Format format) {
    }

    public record Dimension(String field, Bucket bucket, Localized label) {
    }

    public record Measure(String field, Agg agg, String alias, Localized label, Format format) {
    }

    /** {@code value} is a scalar, or an array for IN / BETWEEN (exactly two elements). */
    public record Filter(String field, Op op, JsonNode value) {
    }

    public record Sort(String by, Dir dir) {
    }

    /**
     * Clickable element. {@code column} is required for CELL, {@code label} for CARD_CHIP. Param values
     * are {@code $row.<field>}, {@code $project}, or a literal.
     */
    public record Link(Element element, String column, Localized label, String route,
                       Map<String, String> params) {

        public Link {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    public record Presentation(String style, List<String> colors, Boolean stacked, Boolean legend,
                               Localized emptyText, Boolean compareToPrevious) {

        public Presentation {
            colors = colors == null ? List.of() : List.copyOf(colors);
        }
    }
}
