package com.foremen.controller.model;

import java.util.List;

public record MetadataResponse(List<FieldInfo> fields) {
    public record FieldInfo(
        String name,
        DataType dataType,
        boolean i18n,
        List<FieldInfo> nested
    ) {}

    public enum DataType {
        STRING, NUMBER, DATE, BOOLEAN, ENUM
    }
}
