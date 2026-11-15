package com.foremen.service.procurement.proforma;

/** FOR-11-03 (R2.5): proforma number {@code PF/{yyyy}/{seq:6}}, e.g. {@code PF/2026/000123}. */
public final class ProformaNumberFormatter {

    private ProformaNumberFormatter() {
    }

    public static String format(int year, long seq) {
        if (year < 1000 || year > 9999) {
            throw new IllegalArgumentException("year must have four digits: " + year);
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq must not be negative: " + seq);
        }
        return String.format("PF/%04d/%06d", year, seq);
    }
}
