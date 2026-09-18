package com.foremen.service.pricing;

import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Computes the <b>price range (вилка цен)</b> for construction materials, keyed by the pair
 * ({@link OfferPackageEntity}, {@link ConstructionMaterialTypeEntity}) (FOR-04-17, Requirement 6).
 *
 * <p>For a pair (package {@code P}, type {@code T}) the range is the MIN..MAX of {@code retailNet}
 * across all <b>active</b> construction materials whose {@code type} equals {@code T} and whose
 * {@code packages} set contains {@code P} (Requirement 6.1, 6.2). Because a construction material
 * belongs to several packages (many-to-many), the SAME material contributes to the range of EACH of
 * its packages (Requirement 6.3). Materials with a {@code null} {@code retailNet} are excluded from
 * every pair they would otherwise contribute to (Requirement 6.5), and a pair with no qualifying
 * material yields an empty range {@code PriceRange(null, null)} (Requirement 6.6).
 *
 * <p>The resolver is a pure, total, deterministic function of its input {@code Collection}: it
 * performs no I/O, holds no state, never persists anything (Requirement 6.4), and always produces
 * the same result for the same set of materials (Requirement 6.7). Any {@code null} material, a
 * material with a {@code null} {@code type}, a {@code null}-id type, a {@code null} package, or a
 * {@code null}-id package is skipped so the function is total over any collection.
 */
@Component
public class PriceRangeResolver {

    /** Grouping key: the pair (offer package id, construction material type id). */
    public record PriceRangeKey(Long offerPackageId, Long constructionMaterialTypeId) {
    }

    /**
     * A computed MIN..MAX {@code retailNet} range. An empty range is {@code PriceRange(null, null)}.
     */
    public record PriceRange(BigDecimal min, BigDecimal max) {

        /** The empty range returned for a pair with no qualifying material. */
        public static final PriceRange EMPTY = new PriceRange(null, null);
    }

    /**
     * Computes the price range for every {@code (package, type)} pair reachable from {@code materials}.
     *
     * <p>Only active materials with a non-null {@code retailNet} contribute; each such material is
     * fanned out to {@code (p.id, type.id)} for every package {@code p} in its {@code packages} set,
     * and the per-bucket MIN/MAX are folded via {@link BigDecimal#min}/{@link BigDecimal#max}. Pairs
     * with no qualifying material simply do not appear in the returned map (callers use
     * {@link #rangeFor} or a {@code getOrDefault(key, PriceRange.EMPTY)} for the empty-range
     * semantics).
     *
     * @param materials the construction materials to consider (may be {@code null} or empty)
     * @return a map from {@link PriceRangeKey} to its non-empty {@link PriceRange}
     */
    public Map<PriceRangeKey, PriceRange> compute(Collection<ConstructionMaterialEntity> materials) {
        Map<PriceRangeKey, PriceRange> ranges = new HashMap<>();
        if (materials == null) {
            return ranges;
        }

        for (ConstructionMaterialEntity material : materials) {
            if (!qualifies(material)) {
                continue;
            }
            BigDecimal retailNet = material.getRetailNet();
            Long typeId = material.getType().getId();

            for (OfferPackageEntity offerPackage : material.getPackages()) {
                if (offerPackage == null || offerPackage.getId() == null) {
                    continue;
                }
                PriceRangeKey key = new PriceRangeKey(offerPackage.getId(), typeId);
                ranges.merge(
                        key,
                        new PriceRange(retailNet, retailNet),
                        (existing, candidate) -> new PriceRange(
                                existing.min().min(candidate.min()),
                                existing.max().max(candidate.max())));
            }
        }

        return ranges;
    }

    /**
     * Returns the price range for a single {@code (packageId, typeId)} pair over {@code materials}.
     *
     * @param materials the construction materials to consider (may be {@code null} or empty)
     * @param packageId the target offer package id
     * @param typeId    the target construction material type id
     * @return the computed {@link PriceRange}, or {@link PriceRange#EMPTY} when no material qualifies
     */
    public PriceRange rangeFor(Collection<ConstructionMaterialEntity> materials, Long packageId, Long typeId) {
        return compute(materials)
                .getOrDefault(new PriceRangeKey(packageId, typeId), PriceRange.EMPTY);
    }

    private boolean qualifies(ConstructionMaterialEntity material) {
        if (material == null || !material.isActive() || material.getRetailNet() == null) {
            return false;
        }
        ConstructionMaterialTypeEntity type = material.getType();
        if (type == null || type.getId() == null) {
            return false;
        }
        return material.getPackages() != null;
    }
}
