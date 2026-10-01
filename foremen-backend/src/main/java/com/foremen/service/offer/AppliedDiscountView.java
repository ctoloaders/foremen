package com.foremen.service.offer;

import java.math.BigDecimal;

import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;

/**
 * One applied discount line in the client-facing {@link ClientOfferReadModel} (FOR-05-07,
 * Requirements 15.1, 15.2, 19.3).
 *
 * <p>Confidentiality-safe projection of an {@code OfferDiscount}: it surfaces the discount's scope,
 * kind, configured value, its scope target, and the effective money amount removed — all of which
 * are offer-level, client-visible facts. It holds <b>no</b> cost, margin, worker rate, or
 * estimate-internal unit-price field (Property 21).
 *
 * @param scope    the discount scope ({@code GLOBAL}/{@code CATEGORY}/{@code LINE})
 * @param kind     the discount kind ({@code PERCENT}/{@code ABSOLUTE})
 * @param value    the configured discount value (a percentage or a money amount)
 * @param targetId the scope target id (null for GLOBAL, category id for CATEGORY, line id for LINE)
 * @param amount   the effective money amount removed from the applicable base, or {@code null}
 */
public record AppliedDiscountView(
        DiscountScope scope,
        DiscountKind kind,
        BigDecimal value,
        Long targetId,
        BigDecimal amount) {
}
