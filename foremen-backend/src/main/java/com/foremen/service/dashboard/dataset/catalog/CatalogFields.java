package com.foremen.service.dashboard.dataset.catalog;

import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;

import java.util.ArrayList;
import java.util.List;

/** Small field-building helpers shared by the catalog and material datasets (FOR-10 task 2.4.3). */
final class CatalogFields {

    private CatalogFields() {
    }

    static FieldDescriptor id() {
        return FieldDescriptor.of("id", FieldType.ID);
    }

    static FieldDescriptor createdDate() {
        return FieldDescriptor.of("createdDate", FieldType.DATETIME);
    }

    static FieldDescriptor str(String path) {
        return FieldDescriptor.of(path, FieldType.STRING);
    }

    static FieldDescriptor bool(String path) {
        return FieldDescriptor.of(path, FieldType.BOOLEAN);
    }

    static FieldDescriptor num(String path) {
        return FieldDescriptor.of(path, FieldType.NUMBER);
    }

    /** Money field; {@code currencyPath} is the row currency code path or {@code null}. */
    static FieldDescriptor money(String path, String currencyPath) {
        FieldDescriptor f = FieldDescriptor.of(path, FieldType.MONEY);
        return currencyPath == null ? f : f.withCurrencyPath(currencyPath);
    }

    /** Localised label of a to-one reference: {@code prefix.nameRU} and {@code prefix.namePL}. */
    static List<FieldDescriptor> label(String prefix) {
        return List.of(str(prefix + ".nameRU"), str(prefix + ".namePL"));
    }

    static FieldDescriptor enumField(String path, List<String> values) {
        return FieldDescriptor.of(path, FieldType.ENUM).withEnumValues(values);
    }

    @SafeVarargs
    static List<FieldDescriptor> concat(Object... parts) {
        List<FieldDescriptor> out = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof FieldDescriptor f) {
                out.add(f);
            } else {
                for (Object o : (List<?>) p) {
                    out.add((FieldDescriptor) o);
                }
            }
        }
        return List.copyOf(out);
    }
}
