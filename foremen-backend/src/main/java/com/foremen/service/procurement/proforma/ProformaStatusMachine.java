package com.foremen.service.procurement.proforma;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import static com.foremen.service.procurement.proforma.ProformaStatus.*;

/**
 * FOR-11-03 (R2.3): pure definition of the legal proforma status changes.
 * <pre>
 * DRAFT -> ISSUED | CANCELLED
 * ISSUED <-> PARTIALLY_PAID
 * ISSUED | PARTIALLY_PAID -> PAID
 * PAID -> PARTIALLY_PAID   (only before release)
 * ISSUED -> CANCELLED      (only while paidGross = 0)
 * </pre>
 * CANCELLED is terminal.
 */
public final class ProformaStatusMachine {

    private ProformaStatusMachine() {
    }

    /** Structural exits that do not depend on money or release state. */
    public static Set<ProformaStatus> exits(ProformaStatus status) {
        if (status == null) {
            return EnumSet.noneOf(ProformaStatus.class);
        }
        return switch (status) {
            case DRAFT -> EnumSet.of(ISSUED, CANCELLED);
            case ISSUED -> EnumSet.of(PARTIALLY_PAID, PAID, CANCELLED);
            case PARTIALLY_PAID -> EnumSet.of(ISSUED, PAID);
            case PAID -> EnumSet.of(PARTIALLY_PAID);
            case CANCELLED -> EnumSet.noneOf(ProformaStatus.class);
        };
    }

    /**
     * @param paidGross current paid amount (null counts as zero)
     * @param released  whether {@code releasedAt} is set
     */
    public static boolean canMove(ProformaStatus from, ProformaStatus to, BigDecimal paidGross, boolean released) {
        if (from == null || to == null || !exits(from).contains(to)) {
            return false;
        }
        if (to == CANCELLED && from == ISSUED) {
            return paidGross == null || paidGross.signum() == 0;
        }
        if (from == PAID && to == PARTIALLY_PAID) {
            return !released;
        }
        return true;
    }

    public static boolean isTerminal(ProformaStatus status) {
        return exits(status).isEmpty();
    }
}
