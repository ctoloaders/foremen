package com.foremen.service.dashboard.dataset;

import com.foremen.service.dashboard.config.DatasetSchema.FieldType;

import java.util.List;

/** Dataset catalog entry exposed to the admin widget builder. Never exposes JPA paths. */
public record DatasetCatalogDto(String code, List<String> requiredResources, boolean projectScoped,
                                boolean ownerScoped, boolean alwaysOwn, String defaultDateField,
                                List<FieldDto> fields) {

    public record FieldDto(String name, FieldType type, String labelKey, List<String> enumValues,
                           boolean selectable, boolean filterable, boolean groupable,
                           boolean aggregatable) {
    }

    public static DatasetCatalogDto from(Dataset d) {
        return new DatasetCatalogDto(
                d.code(),
                d.requiredResources().stream().sorted().toList(),
                d.projectPath().isPresent(),
                d.ownerPath().isPresent(),
                d.alwaysOwn(),
                d.defaultDateField().orElse(null),
                d.allFields().stream()
                        .map(f -> new FieldDto(f.name(), f.type(), f.labelKey(), f.enumValues(),
                                f.selectable(), f.filterable(), f.groupable(), f.aggregatable()))
                        .toList());
    }
}
