package com.foremen.service.estimate.margins;

import java.math.BigDecimal;

/**
 * FOR-05-06 (design §B3) — a profitability result: the absolute margin amount plus its fraction of the
 * reference base (the offer for labour, the retail for materials).
 *
 * <p>This is the minimal result type produced by the {@link MarginCostService} cost core. Task 4.3's
 * read-model {@code MoneyMargin} record aligns with this shape (same {@code amount} + {@code pct}
 * fields) so the assembler can adapt one to the other without a formula change.
 *
 * <p>A {@code null} {@link #amount()} means the margin is <strong>unavailable</strong> (a null/zero
 * offer, or a null price) — the frontend renders it as {@code —} and it is excluded from totals
 * (R2.4, R4.5). {@link #pct()} is {@code null} whenever the amount is unavailable or the reference
 * base is null/zero (no division by zero, no fabricated percentage).
 *
 * @param amount the absolute margin ({@code reference − cost}), or {@code null} when unavailable
 * @param pct    the margin as a fraction of the reference base ({@code amount / reference}), or
 *               {@code null} when unavailable or the reference is null/zero
 */
public record Margin(BigDecimal amount, BigDecimal pct) {

    /** The unavailable margin — rendered {@code —} and excluded from totals (R2.4, R4.5). */
    public static final Margin UNAVAILABLE = new Margin(null, null);
}
