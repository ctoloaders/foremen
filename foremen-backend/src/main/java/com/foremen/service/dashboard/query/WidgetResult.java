package com.foremen.service.dashboard.query;

import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.config.WidgetConfig.Format;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of one widget query. Money cells are {@code {"amount": "123.45", "currency": "PLN"}},
 * dates are ISO strings, enums are their names.
 *
 * @param totals   reserved for grouped totals, currently always {@code null}
 * @param previous measure values of the previous equal-length period (KPI compare), or {@code null}
 */
public record WidgetResult(List<Column> columns, List<Map<String, Object>> rows,
                           Map<String, Object> totals, Map<String, Object> previous,
                           boolean truncated, Instant generatedAt) {

    public WidgetResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** @param label localized label from the config, or null (client falls back to {@code labelKey}) */
    public record Column(String key, FieldType type, String label, String labelKey, Format format) {
    }
}
