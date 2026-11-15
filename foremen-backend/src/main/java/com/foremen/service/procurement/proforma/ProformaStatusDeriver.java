package com.foremen.service.procurement.proforma;

import java.math.BigDecimal;

/**
 * FOR-11-03 (R8.1): derives the status from the money. {@code 0 -> ISSUED},
 * {@code 0 < paid < total -> PARTIALLY_PAID}, {@code paid >= total -> PAID}.
 * A {@code DRAFT} or {@code CANCELLED} proforma is never moved.
 */
public final class ProformaStatusDeriver {

    private ProformaStatusDeriver() {
    }

    public static ProformaStatus derive(BigDecimal totalGross, BigDecimal paidGross, ProformaStatus current) {
        if (current == null || current == ProformaStatus.DRAFT || current == ProformaStatus.CANCELLED) {
            return current;
        }
        BigDecimal paid = paidGross == null ? BigDecimal.ZERO : paidGross;
        BigDecimal total = totalGross == null ? BigDecimal.ZERO : totalGross;
        if (paid.signum() <= 0) {
            return ProformaStatus.ISSUED;
        }
        return paid.compareTo(total) >= 0 ? ProformaStatus.PAID : ProformaStatus.PARTIALLY_PAID;
    }
}
