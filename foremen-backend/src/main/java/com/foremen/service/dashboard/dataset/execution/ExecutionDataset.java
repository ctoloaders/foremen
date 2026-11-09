package com.foremen.service.dashboard.dataset.execution;

import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Common shape of the FOR-10 execution and procurement datasets (code, resource, root entity, field
 * catalog, default date field). Subclasses add the scope paths.
 */
abstract class ExecutionDataset implements Dataset {

    private final String code;
    private final String resource;
    private final Class<?> rootEntity;
    private final String dateField;
    private final List<FieldDescriptor> fields;

    ExecutionDataset(String code, String resource, Class<?> rootEntity, String dateField,
                     List<FieldDescriptor> fields) {
        this.code = code;
        this.resource = resource;
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
        return Set.of(resource);
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
}
