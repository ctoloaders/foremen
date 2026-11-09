package com.foremen.service.dashboard.config;

import java.util.List;
import java.util.Optional;

/**
 * Minimal, read-only view of a dataset used by {@link WidgetConfigValidator}. The dataset framework
 * (task 2.3) adapts its {@code Dataset} to this record; it is deliberately independent of it.
 *
 * @param code          dataset code
 * @param projectScoped true when the dataset declares a project path (needed for SINGLE_PROJECT widgets)
 * @param fields        field catalog
 */
public record DatasetSchema(String code, boolean projectScoped, List<Field> fields) {

    public DatasetSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public Optional<Field> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    /** Field data type, mirrors the design's FieldDescriptor types. */
    public enum FieldType { STRING, NUMBER, MONEY, DATE, DATETIME, ENUM, BOOLEAN, ID }

    /** Capability flags of a dataset field. */
    public record Field(String name, FieldType type, boolean selectable, boolean filterable,
                        boolean groupable, boolean aggregatable) {
    }
}
