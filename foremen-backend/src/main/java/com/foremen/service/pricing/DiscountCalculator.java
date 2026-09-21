package com.foremen.service.pricing;

import com.foremen.dao.model.DiscountKind;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Derives the <b>effective unit price</b> of a per-package project price from its
 * {@code (originalUnitPrice, discountKind, discountValue)} triple (FOR-05-03, Requirement 5;
 * design §6.3 {@code applyDiscount}).
 *
 * <p>The calculator is a pure, total, deterministic function of its three inputs: it performs no
 * I/O, holds no state, and always returns the same result for the same inputs — mirroring the
 * {@link EffectivePriceResolver} convention of a stateless Spring {@code @Component} exercised
 * directly by property-based tests.
 *
 * <p>Rule (Requirement 5.2, 5.3; design §6.3):
 * <ol>
 *   <li>If {@code originalUnitPrice} is {@code null} (the package is unpriced), return {@code null}
 *       — an unpriced package stays unpriced regardless of any discount fields.</li>
 *   <li>If {@code discountKind} is {@code null}, or {@code discountValue} is {@code null} or zero,
 *       return {@code originalUnitPrice} unchanged — a zero/absent discount is a valid, neutral
 *       state (Requirement 5.2).</li>
 *   <li>Otherwise derive {@code effective} from {@code originalUnitPrice} and the discount:
 *       {@code PERCENT} multiplies by {@code (1 - discountValue / 100)}; {@code ABSOLUTE} subtracts
 *       {@code discountValue} — then floor at zero and round to 2 decimal places, HALF_UP
 *       (Requirement 5.3).</li>
 * </ol>
 */
@Component
public class DiscountCalculator {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Derives the effective unit price from {@code (originalUnitPrice, discountKind,
     * discountValue)}, per design §6.3.
     *
     * @param originalUnitPrice the price copied from the catalog at add-time; {@code null} when the
     *                          package is unpriced
     * @param discountKind      {@code PERCENT} or {@code ABSOLUTE}; {@code null} means no discount
     * @param discountValue     the discount magnitude; {@code null} or zero means no discount
     * @return the effective unit price, rounded to 2 decimals and never negative; {@code null} when
     *         {@code originalUnitPrice} is {@code null}
     */
    public BigDecimal applyDiscount(BigDecimal originalUnitPrice, DiscountKind discountKind, BigDecimal discountValue) {
        if (originalUnitPrice == null) {
            return null;
        }

        if (discountKind == null || discountValue == null || discountValue.signum() == 0) {
            return round2(originalUnitPrice);
        }

        BigDecimal effective;
        if (discountKind == DiscountKind.PERCENT) {
            BigDecimal factor = BigDecimal.ONE.subtract(discountValue.divide(HUNDRED, 10, RoundingMode.HALF_UP));
            effective = originalUnitPrice.multiply(factor);
        } else {
            effective = originalUnitPrice.subtract(discountValue);
        }

        if (effective.signum() < 0) {
            effective = BigDecimal.ZERO;
        }

        return round2(effective);
    }

    private BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
