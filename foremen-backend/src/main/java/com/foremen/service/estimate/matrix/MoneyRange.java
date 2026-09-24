package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;

/**
 * A min..max money band (net PLN) of the Estimate tab read model (FOR-05-05, design §B6). Collapsed
 * to a single value when {@code min} equals {@code max} — the convention used throughout: a cell /
 * subtotal / total collapses when every contributing material line has a concrete product chosen
 * (R4.3, R6.4, R14.2). Mirrors the frontend {@code MoneyRange}.
 *
 * @param min the range lower bound (net PLN)
 * @param max the range upper bound (net PLN); equals {@code min} for a collapsed range
 */
public record MoneyRange(BigDecimal min, BigDecimal max) {

    /** The zero range {@code 0..0} — the neutral value for an unassigned cell or an empty group. */
    public static final MoneyRange ZERO = new MoneyRange(BigDecimal.ZERO, BigDecimal.ZERO);

    /** A collapsed range at {@code value} ({@code value..value}). */
    public static MoneyRange point(BigDecimal value) {
        BigDecimal v = value != null ? value : BigDecimal.ZERO;
        return new MoneyRange(v, v);
    }
}
