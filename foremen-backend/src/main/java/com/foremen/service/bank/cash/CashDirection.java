package com.foremen.service.bank.cash;

import java.math.BigDecimal;

/**
 * FOR-07-04 (Requirement 27.3) — the direction of a manual cash entry. Cash out ({@link #EXPENSE}) is
 * stored as a negative amount, cash in ({@link #INCOME}) as a positive one.
 */
public enum CashDirection {
    EXPENSE,
    INCOME;

    /** The signed ledger amount for a positive {@code magnitude}. */
    public BigDecimal signed(BigDecimal magnitude) {
        return this == EXPENSE ? magnitude.negate() : magnitude;
    }

    /** The direction of a signed ledger amount (never zero). */
    public static CashDirection of(BigDecimal signedAmount) {
        return signedAmount.signum() < 0 ? EXPENSE : INCOME;
    }
}
