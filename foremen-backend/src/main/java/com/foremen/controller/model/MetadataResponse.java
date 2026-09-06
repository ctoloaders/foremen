package com.foremen.controller.model;

import java.util.List;

public record MetadataResponse(List<FieldInfo> fields) {
    public record FieldInfo(
        String name,
        DataType dataType,
        boolean i18n,
        List<FieldInfo> nested,
        ReferenceInfo reference
    ) {
        /**
         * Backward-compatible constructor for non-reference fields.
         * Leaves {@code reference} null so existing call sites and scalar
         * fields keep their prior behavior unchanged.
         */
        public FieldInfo(String name, DataType dataType, boolean i18n, List<FieldInfo> nested) {
            this(name, dataType, i18n, nested, null);
        }
    }

    /**
     * Optional descriptor emitted for {@code @ManyToOne}/{@code @OneToOne}
     * reference fields. Marks a field as a reference and carries the metadata
     * the frontend needs to render a reference filter: the target resource,
     * its options endpoint, the display label field (and whether it is i18n),
     * and the id filter path composed with the existing query grammar.
     */
    public record ReferenceInfo(
        String targetResource,
        String optionsPath,
        String labelField,
        boolean labelI18n,
        String idPath
    ) {}

    public enum DataType {
        STRING, NUMBER, DATE, BOOLEAN, ENUM
    }
}
