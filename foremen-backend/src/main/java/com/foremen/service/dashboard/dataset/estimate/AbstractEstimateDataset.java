package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.FieldDescriptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Declarative base for the estimate / offer / amendment / document datasets. */
abstract class AbstractEstimateDataset implements Dataset {

    private final String code;
    private final String resource;
    private final Class<?> root;
    private final String projectPath;
    private final String dateField;
    private final List<FieldDescriptor> fields;

    AbstractEstimateDataset(String code, String resource, Class<?> root, String projectPath,
                            String dateField, List<FieldDescriptor> fields) {
        this.code = code;
        this.resource = resource;
        this.root = root;
        this.projectPath = projectPath;
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
        return root;
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.ofNullable(projectPath);
    }

    @Override
    public Optional<String> defaultDateField() {
        return Optional.ofNullable(dateField);
    }

    @Override
    public List<FieldDescriptor> fields() {
        return fields;
    }
}
