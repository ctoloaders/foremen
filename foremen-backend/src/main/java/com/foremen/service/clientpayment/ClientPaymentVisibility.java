package com.foremen.service.clientpayment;

import java.util.Set;

/**
 * FOR-11-06 (R1.7, R3.1): the pure per-role kind rule of the client-payment list. ADMIN, MANAGER and
 * FINANCIER see both kinds; FOREMAN only {@code WORKS}; BUYER only {@code MATERIALS}; CLIENT a
 * client-safe subset (published works tranches, issued / partially paid / paid materials); every other
 * role sees nothing.
 */
public enum ClientPaymentVisibility {
    ALL,
    WORKS_ONLY,
    MATERIALS_ONLY,
    CLIENT,
    NONE;

    /** Stored MATERIALS statuses visible to a CLIENT (R3.1). */
    public static final Set<String> CLIENT_MATERIALS_STATUSES = Set.of("ISSUED", "PARTIALLY_PAID", "PAID");

    public static ClientPaymentVisibility forRole(String roleCode) {
        if (roleCode == null) {
            return NONE;
        }
        return switch (roleCode) {
            case "ADMIN", "MANAGER", "FINANCIER" -> ALL;
            case "FOREMAN" -> WORKS_ONLY;
            case "BUYER" -> MATERIALS_ONLY;
            case "CLIENT" -> CLIENT;
            default -> NONE;
        };
    }

    /** True when the role may see rows of the kind at all (a CLIENT sees both, narrowed by status). */
    public boolean allows(ClientPaymentKind kind) {
        return switch (this) {
            case ALL, CLIENT -> true;
            case WORKS_ONLY -> kind == ClientPaymentKind.WORKS;
            case MATERIALS_ONLY -> kind == ClientPaymentKind.MATERIALS;
            case NONE -> false;
        };
    }
}
