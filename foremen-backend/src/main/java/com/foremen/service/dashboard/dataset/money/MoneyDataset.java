package com.foremen.service.dashboard.dataset.money;

import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Common shape of the FOR-10 money datasets (payments, payroll, bank): code, resource, root entity,
 * field catalog and default date field. Subclasses add scope paths.
 */
abstract class MoneyDataset implements Dataset {

    private final String code;
    private final Set<String> resources;
    private final Class<?> rootEntity;
    private final String dateField;
    private final List<FieldDescriptor> fields;

    MoneyDataset(String code, String resource, Class<?> rootEntity, String dateField,
                 List<FieldDescriptor> fields) {
        this.code = code;
        this.resources = Set.of(resource);
        this.rootEntity = rootEntity;
        this.dateField = dateField;
        this.fields = List.copyOf(fields);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Set<String> requiredResources() {
        return resources;
    }

    @Override
    public Class<?> rootEntity() {
        return rootEntity;
    }

    @Override
    public List<FieldDescriptor> fields() {
        return fields;
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.ofNullable(dateField);
    }

    static FieldDescriptor enumField(String name, Class<? extends Enum<?>> type) {
        return FieldDescriptor.of(name, name, FieldType.ENUM).withEnumValues(
                Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
    }

    /** Money field; {@code currencyPath == null} means the company default currency. */
    static FieldDescriptor money(String name, String path, String currencyPath) {
        FieldDescriptor f = FieldDescriptor.of(name, path, FieldType.MONEY);
        return currencyPath == null ? f : f.withCurrencyPath(currencyPath);
    }
}
