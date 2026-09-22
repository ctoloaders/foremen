package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Computes the assortment's <b>package zł/m²</b> price (FOR-05-04, design §6.6
 * {@code packageZlM2}) from the assortment line items' {@code avgPrice}/{@code qtyRef50} fields,
 * grouped by assortment group id.
 *
 * <p>The resolver is a pure, total, deterministic function of its inputs: it performs no I/O,
 * holds no state, and always returns the same result for the same inputs — mirroring the
 * {@link MaxCollapseRule} / {@link DiscountCalculator} convention of a stateless Spring
 * {@code @Component} exercised directly by property-based tests.
 *
 * <p>It intentionally does not depend on the {@code AssortmentGroupEntity}/
 * {@code AssortmentLineItemEntity} JPA entities (FOR-05-04 tasks 13.1/13.2, not yet available at
 * the time this resolver was authored). Instead it operates over the small {@link Line} shape
 * carrying just the fields the formula needs ({@code groupId}, {@code packageCode},
 * {@code avgPrice}, {@code qtyRef50}). Callers (e.g. {@code AssortmentGroupService}/
 * {@code AssortmentLineItemService}, task 18.1) adapt the real entities to this shape.
 *
 * <p>Rule (design §6.6, Requirement 6.3, 6.4, 6.6):
 * <ol>
 *   <li>{@code groupContribution(groupLines, pkgCode)} = round2(Σ {@code avgPrice × qtyRef50} for
 *       the group's lines restricted to {@code pkgCode}, ÷ 50).</li>
 *   <li>{@code packageZlM2(linesByGroup, pkgCode)} = round2(Σ of every group's
 *       {@code groupContribution} for {@code pkgCode}).</li>
 * </ol>
 *
 * <p>Both methods recompute their result from the collections passed in on every call — nothing
 * is cached (Requirement 6.5) — and neither reads nor requires any "typical product" field, so
 * the result is unaffected by that field's presence or absence (Requirement 6.6, 6.8).
 */
@Component
public class PackageZlM2Resolver {

    private static final int SCALE = 2;
    private static final BigDecimal REF_AREA = BigDecimal.valueOf(50);

    /**
     * A minimal, entity-independent view of an assortment line item: the fields
     * {@link PackageZlM2Resolver} needs to compute the zł/m² contribution.
     *
     * @param groupId     the id of the {@code AssortmentGroup} this line belongs to
     * @param packageCode the {@code OfferPackage} code this line's prices apply to
     * @param avgPrice    the line's average price; treated as the "line total" source per
     *                    design §6.6 (min/max are exposed for the review band but not summed
     *                    here)
     * @param qtyRef50    the reference quantity for a 50 m² unit
     */
    public record Line(Long groupId, String packageCode, BigDecimal avgPrice, BigDecimal qtyRef50) {
    }

    /**
     * Returns the zł/m² contribution of a single assortment group for {@code pkgCode}: the sum of
     * {@code avgPrice × qtyRef50} over the group's lines whose {@code packageCode} matches
     * {@code pkgCode}, divided by the 50 m² reference area and rounded to 2 decimals, HALF_UP
     * (design §6.6, Requirement 6.3).
     *
     * @param groupLines a single assortment group's line items (may include lines for other
     *                   packages, which are filtered out here); {@code null} or empty yields zero
     * @param pkgCode    the target offer package's code
     * @return the group's zł/m² contribution for {@code pkgCode}, rounded to 2 decimals
     */
    public BigDecimal groupContribution(Collection<Line> groupLines, String pkgCode) {
        if (groupLines == null || groupLines.isEmpty() || pkgCode == null) {
            return round2(BigDecimal.ZERO);
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (Line line : groupLines) {
            if (line == null || !pkgCode.equals(line.packageCode())) {
                continue;
            }
            BigDecimal avgPrice = line.avgPrice();
            BigDecimal qtyRef50 = line.qtyRef50();
            if (avgPrice == null || qtyRef50 == null) {
                continue;
            }
            sum = sum.add(avgPrice.multiply(qtyRef50));
        }

        return round2(sum.divide(REF_AREA, 10, RoundingMode.HALF_UP));
    }

    /**
     * Returns the package zł/m² price for {@code pkgCode}: the sum of {@link #groupContribution}
     * over every group in {@code linesByGroup}, rounded to 2 decimals, HALF_UP (design §6.6,
     * Requirement 6.4). Recomputed from {@code linesByGroup} on every call — never cached
     * (Requirement 6.5) — and independent of any "typical product" field on the underlying line
     * items, which this method never reads (Requirement 6.6, 6.8).
     *
     * @param linesByGroup the current assortment line items, keyed by {@code AssortmentGroup} id;
     *                      {@code null} or empty yields zero
     * @param pkgCode      the target offer package's code
     * @return the package's zł/m² price for {@code pkgCode}, rounded to 2 decimals
     */
    public BigDecimal packageZlM2(Map<Long, ? extends Collection<Line>> linesByGroup, String pkgCode) {
        if (linesByGroup == null || linesByGroup.isEmpty() || pkgCode == null) {
            return round2(BigDecimal.ZERO);
        }

        BigDecimal total = BigDecimal.ZERO;
        for (Collection<Line> groupLines : linesByGroup.values()) {
            total = total.add(groupContribution(groupLines, pkgCode));
        }

        return round2(total);
    }

    private BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
