package com.foremen.service.pricing;

import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

/**
 * Resolves a work item's <b>effective price</b> for a given offer package over that item's already
 * loaded {@link WorkPackagePriceEntity} collection, applying the <b>max-fallback</b> rule
 * (FOR-04-12b, Requirement 3).
 *
 * <p>The resolver is a pure, total, deterministic function of {@code (packagePrices, packageCode)}:
 * it performs no I/O, holds no state, and always returns the same result for the same inputs
 * (Requirement 3.5). It operates on the {@code WorkPackagePrice} data that has already been loaded
 * into the aggregator's collection, so it can be exercised directly as a static-style helper by the
 * property-based tests while still being wired as a stateless Spring {@code @Component}.
 *
 * <p>Rule (Requirement 3.2–3.4, 3.6, 3.7):
 * <ol>
 *   <li>If a member's {@code offerPackage.code} equals {@code packageCode}, return that member's
 *       {@code netPrice} (the exact package price).</li>
 *   <li>Otherwise, if the collection is non-empty, return the maximum {@code netPrice} across all
 *       members (the max fallback).</li>
 *   <li>Otherwise (empty collection, or the work item has no {@code WorkPrice}), return
 *       {@link Optional#empty()} — the work item is unpriced.</li>
 * </ol>
 *
 * <p>Because branch 1 returns an actual member price and branch 2 returns the maximum member price,
 * the result is always {@code <= max(netPrice)} over the collection (Requirement 3.7). A
 * single-member collection returns that one price for every package (Requirement 3.6): branch 1 for
 * the member's own package, branch 2 for every other package. The resolver is keyed by {@code code}
 * because it serves the row DTO's code-keyed prices map; it is unrelated to the id-keyed Pivot_Key
 * used for filter/sort.
 */
@Component
public class EffectivePriceResolver {

    /**
     * Returns the effective {@code netPrice} for {@code packageCode} over {@code packagePrices}, or
     * {@link Optional#empty()} when the work item is unpriced (empty/{@code null} collection).
     *
     * @param packagePrices the work item's {@code WorkPackagePrice} collection (may be {@code null}
     *                      or empty for an unpriced item)
     * @param packageCode   the target offer package's {@code code}
     * @return the effective price, or empty when unpriced
     */
    public Optional<BigDecimal> resolve(Collection<WorkPackagePriceEntity> packagePrices, String packageCode) {
        if (packagePrices == null || packagePrices.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal max = null;
        for (WorkPackagePriceEntity price : packagePrices) {
            if (price == null) {
                continue;
            }
            BigDecimal netPrice = price.getNetPrice();
            if (netPrice == null) {
                continue;
            }
            if (packageCode != null && matchesPackage(price, packageCode)) {
                return Optional.of(netPrice);
            }
            if (max == null || netPrice.compareTo(max) > 0) {
                max = netPrice;
            }
        }

        return Optional.ofNullable(max);
    }

    private boolean matchesPackage(WorkPackagePriceEntity price, String packageCode) {
        OfferPackageEntity offerPackage = price.getOfferPackage();
        return offerPackage != null && packageCode.equals(offerPackage.getCode());
    }
}
