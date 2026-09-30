package com.foremen.service.estimate.margins;

import java.math.BigDecimal;

/**
 * FOR-05-06 (design "Read-model DTOs") — a read-model profitability value: the absolute margin amount
 * plus its fraction of the reference base (the offer for labour, the retail for materials).
 *
 * <p>Structurally aligned with the cost core's {@link Margin} record (same {@code amount} + {@code pct}
 * fields), so the {@link MarginsListAssembler} converts a {@link Margin} to a {@link MoneyMargin} 1:1
 * via {@link #of(Margin)} without any formula change. This is the type serialized in the read model
 * and mirrored by the frontend {@code MoneyMargin}.
 *
 * <p>A {@code null} {@link #amount()} means the margin is <strong>unavailable</strong> (a null/zero
 * offer, or a null price): the frontend renders it as {@code —} and it is excluded from totals
 * (R2.4, R4.5, R5.4). {@link #pct()} is {@code null} whenever the amount is unavailable or the
 * reference base is null/zero (no division by zero, no fabricated percentage).
 *
 * @param amount the absolute margin ({@code reference − cost}), or {@code null} when unavailable
 * @param pct    the margin as a fraction of the reference base ({@code amount / reference}), or
 *               {@code null} when unavailable or the reference is null/zero
 */
public record MoneyMargin(BigDecimal amount, BigDecimal pct) {

    /** The unavailable margin — rendered {@code —} and excluded from totals (R2.4, R4.5, R5.4). */
    public static final MoneyMargin UNAVAILABLE = new MoneyMargin(null, null);

    /**
     * Adapt a cost-core {@link Margin} to a read-model {@link MoneyMargin} 1:1 (same {@code amount} +
     * {@code pct}). A {@code null} margin ⇒ {@link #UNAVAILABLE}.
     *
     * @param margin the cost-core margin, or {@code null}
     * @return the equivalent read-model money margin
     */
    public static MoneyMargin of(Margin margin) {
        if (margin == null) {
            return UNAVAILABLE;
        }
        return new MoneyMargin(margin.amount(), margin.pct());
    }
}
