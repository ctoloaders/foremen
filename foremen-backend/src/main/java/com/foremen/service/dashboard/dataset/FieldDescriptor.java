package com.foremen.service.dashboard.dataset;

import com.foremen.service.dashboard.config.DatasetSchema;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One config-facing field of a {@link Dataset} (FOR-10 design "Dataset registry and query engine").
 *
 * <p>{@code jpaPath} may traverse to-one associations only. Computed fields (and LOOKUP fields, see
 * {@link LookupField}) carry a {@link FieldExpression} instead of a path.
 *
 * @param name         config-facing name, e.g. {@code project.name}
 * @param jpaPath      JPA dot-path from the root entity, {@code null} for computed fields
 * @param type         data type
 * @param enumValues   allowed values for {@link FieldType#ENUM}
 * @param labelKey     i18n key of the field label
 * @param currencyPath optional JPA path of the row currency code for {@link FieldType#MONEY} fields
 * @param computed     optional expression builder, replaces {@code jpaPath}
 */
public record FieldDescriptor(String name, String jpaPath, FieldType type, List<String> enumValues,
                              String labelKey, boolean selectable, boolean filterable,
                              boolean groupable, boolean aggregatable, String currencyPath,
                              FieldExpression computed) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$");

    public FieldDescriptor {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid field name: " + name);
        }
        if (type == null) {
            throw new IllegalArgumentException("Field type is required: " + name);
        }
        if ((jpaPath == null) == (computed == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of jpaPath / computed must be set for field " + name);
        }
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        labelKey = labelKey == null ? "dashboard.field." + name : labelKey;
    }

    /** Field over a JPA path with default capabilities for its type. */
    public static FieldDescriptor of(String name, String jpaPath, FieldType type) {
        return new FieldDescriptor(name, jpaPath, type, List.of(), null, true, true, true,
                defaultAggregatable(type), null, null);
    }

    /** Field with the same name as its JPA path. */
    public static FieldDescriptor of(String path, FieldType type) {
        return of(path, path, type);
    }

    /** Computed field (expression instead of a path). */
    public static FieldDescriptor computed(String name, FieldType type, FieldExpression expression) {
        return new FieldDescriptor(name, null, type, List.of(), null, true, true, true,
                defaultAggregatable(type), null, expression);
    }

    public FieldDescriptor withEnumValues(List<String> values) {
        return new FieldDescriptor(name, jpaPath, type, values, labelKey, selectable, filterable,
                groupable, aggregatable, currencyPath, computed);
    }

    public FieldDescriptor withLabelKey(String key) {
        return new FieldDescriptor(name, jpaPath, type, enumValues, key, selectable, filterable,
                groupable, aggregatable, currencyPath, computed);
    }

    public FieldDescriptor withCurrencyPath(String path) {
        return new FieldDescriptor(name, jpaPath, type, enumValues, labelKey, selectable, filterable,
                groupable, aggregatable, path, computed);
    }

    public FieldDescriptor withFlags(boolean selectable, boolean filterable, boolean groupable,
                                     boolean aggregatable) {
        return new FieldDescriptor(name, jpaPath, type, enumValues, labelKey, selectable, filterable,
                groupable, aggregatable, currencyPath, computed);
    }

    public DatasetSchema.Field toSchemaField() {
        return new DatasetSchema.Field(name, type, selectable, filterable, groupable, aggregatable);
    }

    private static boolean defaultAggregatable(FieldType type) {
        return type == FieldType.NUMBER || type == FieldType.MONEY || type == FieldType.ID
                || type == FieldType.DATE || type == FieldType.DATETIME;
    }
}
