package com.foremen.service.estimate.materials;

import java.math.BigDecimal;

/**
 * A net + brutto money pair (PLN) of the Materials tab read model (FOR-05-05b, design "Read model
 * DTOs"). The {@code net} value is the estimate line's chosen-product net price used verbatim (never
 * rescaled, R7.2); {@code brutto} is that net with the applicable VAT applied
 * ({@code net × (1 + vat/100)}). A {@code null} net/brutto renders as {@code —} on the frontend and is
 * excluded from money totals (R12.4). Mirrors the frontend {@code MoneyBrutto}.
 *
 * @param net    the net money (verbatim from the estimate line), or {@code null} when the product has
 *               no net price (R12.4)
 * @param brutto the brutto money ({@code net × (1 + vat/100)}), or {@code null} when net is {@code null}
 */
public record MoneyBrutto(BigDecimal net, BigDecimal brutto) {

    /** The {@code null} money pair — no net price, rendered {@code —} and excluded from totals (R12.4). */
    public static final MoneyBrutto EMPTY = new MoneyBrutto(null, null);

    /** The zero money pair {@code 0/0} — the neutral value for a totals fold. */
    public static final MoneyBrutto ZERO = new MoneyBrutto(BigDecimal.ZERO, BigDecimal.ZERO);
}
