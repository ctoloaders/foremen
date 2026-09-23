package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

import org.springframework.stereotype.Component;

/**
 * Computes the assortment's <b>package zł/m²</b> price (FOR-05-04-UI) for a single price band
 * (min/avg/max) from a flat list of per-position contributions.
 *
 * <p><b>Per-position quantity (FOR-05-04-UI per-price override).</b> Each position contributes
 * {@code price × quantity}, where the quantity is that position's OPTIONAL per-band override, or —
 * when the position has no override for the band — the owning group's single {@code referenceQty}
 * (the group value is the default). The reference quantity is therefore resolved per position
 * BEFORE it reaches this resolver; the resolver only sums {@code price × quantity} and divides by
 * the 50 m² reference area:
 *
 * <pre>
 *   packageZlM2(band) = round2( ( Σ over positions( price × quantity ) ) ÷ 50 )
 * </pre>
 *
 * <p>The headline package zł/m² persisted into {@code offer_packages.zl_m2} is the <b>MAX</b> band
 * (FOR-05-04-UI); the min/avg bands are still computed for the review range. The band selection is
 * the caller's concern — this resolver is band-agnostic and simply sums the {@link Contribution}s
 * it is handed.
 *
 * <p>The resolver is a pure, total, deterministic function of its inputs: it performs no I/O,
 * holds no state, and always returns the same result for the same inputs. A {@code null} price or
 * {@code null} quantity in a contribution contributes 0.
 */
@Component
public class PackageZlM2Resolver {

    private static final int SCALE = 2;
    private static final BigDecimal REF_AREA = BigDecimal.valueOf(50);

    /**
     * One position's contribution to a package total for one band: the position's price for the
     * band and the effective quantity to multiply it by (the per-band override, or the group's
     * {@code referenceQty} when there is no override — resolved by the caller).
     *
     * @param price    the position's price for the band; {@code null} contributes 0
     * @param quantity the effective quantity (override or group reference qty); {@code null}
     *                 contributes 0
     */
    public record Contribution(BigDecimal price, BigDecimal quantity) {
    }

    /**
     * Returns the package zł/m² for one band: {@code round2( Σ (price × quantity) ÷ 50 )} over the
     * given contributions, HALF_UP. A {@code null} price or quantity contributes 0. Recomputed
     * from {@code contributions} on every call — never cached (Requirement 6.5).
     *
     * @param contributions every position's {@code (price, quantity)} for the band; {@code null}
     *                      or empty yields zero
     * @return the band's package zł/m², rounded to 2 decimals
     */
    public BigDecimal packageZlM2(Collection<Contribution> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            return round2(BigDecimal.ZERO);
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (Contribution c : contributions) {
            if (c == null || c.price() == null || c.quantity() == null) {
                continue;
            }
            sum = sum.add(c.price().multiply(c.quantity()));
        }

        return round2(sum.divide(REF_AREA, 10, RoundingMode.HALF_UP));
    }

    private BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
