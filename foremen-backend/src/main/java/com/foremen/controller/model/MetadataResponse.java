package com.foremen.controller.model;

import java.util.List;

public record MetadataResponse(List<FieldInfo> fields) {
    public record FieldInfo(
        String name,
        DataType dataType,
        boolean i18n,
        List<FieldInfo> nested,
        ReferenceInfo reference,
        PivotInfo pivot
    ) {
        /**
         * Backward-compatible constructor for non-reference fields.
         * Leaves {@code reference} and {@code pivot} null so existing call sites and scalar
         * fields keep their prior behavior unchanged.
         */
        public FieldInfo(String name, DataType dataType, boolean i18n, List<FieldInfo> nested) {
            this(name, dataType, i18n, nested, null, null);
        }

        /**
         * Backward-compatible constructor for reference fields (pre-pivot call sites).
         * Leaves {@code pivot} null.
         */
        public FieldInfo(String name, DataType dataType, boolean i18n, List<FieldInfo> nested,
                         ReferenceInfo reference) {
            this(name, dataType, i18n, nested, reference, null);
        }
    }

    /**
     * Optional descriptor emitted for a synthetic <em>pivot</em> field — a query key that does not
     * map to a plain persistent column but to a collection element discriminated by a numeric id
     * (FOR-04-12b work-prices pivot). The field {@code name} is the exact {@code Pivot_Key} the
     * frontend sends back as filter/sort (e.g. {@code prices.5.netPrice}); this descriptor carries
     * the numeric discriminator {@code id} and the localized labels so the frontend can build the
     * column header and know which id to use.
     *
     * @param id     the numeric discriminator embedded in the pivot key (here {@code offerPackage.id})
     * @param labelRU the Russian label for the pivot (here the offer package {@code nameRU})
     * @param labelPL the Polish label for the pivot (here the offer package {@code namePL})
     * @param sortable whether the frontend may sort by this pivot key
     * @param filterable whether the frontend may filter by this pivot key
     */
    public record PivotInfo(
        Long id,
        String labelRU,
        String labelPL,
        boolean sortable,
        boolean filterable
    ) {}

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
