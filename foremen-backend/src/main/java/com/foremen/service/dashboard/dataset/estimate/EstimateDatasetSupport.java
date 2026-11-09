package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;

import java.util.Arrays;
import java.util.List;

/** Small helpers shared by the estimate / offer / amendment / document datasets. */
final class EstimateDatasetSupport {

    private EstimateDatasetSupport() {
    }

    static List<String> names(Class<? extends Enum<?>> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList();
    }

    static FieldDescriptor enumField(String name, String path, Class<? extends Enum<?>> type) {
        return FieldDescriptor.of(name, path, FieldType.ENUM).withEnumValues(names(type))
                .withFlags(true, true, true, false);
    }

    /** Amount field priced in the currency found at {@code currencyPath} (null = company default). */
    static FieldDescriptor money(String name, String path, String currencyPath) {
        return FieldDescriptor.of(name, path, FieldType.MONEY).withCurrencyPath(currencyPath)
                .withFlags(true, true, false, true);
    }

    /** Id + display name of the project reached through {@code prefix} (e.g. {@code "estimate.project"}). */
    static List<FieldDescriptor> projectFields(String prefix) {
        return List.of(
                FieldDescriptor.of(prefix + ".id", FieldType.ID),
                FieldDescriptor.of(prefix + ".name", FieldType.STRING).withFlags(true, true, true, false));
    }

    static FieldDescriptor string(String name, String path) {
        return FieldDescriptor.of(name, path, FieldType.STRING).withFlags(true, true, true, false);
    }
}
