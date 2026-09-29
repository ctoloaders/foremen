package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;

/**
 * Computes the <b>price range (вилка цен)</b> for finishing materials, keyed by
 * {@link MaterialTypeEntity} with an optional {@link OfferPackageEntity} scope
 * (FOR-05-05, Requirement 12.5, 3.4, 6.1).
 *
 * <p>This is the finishing-branch analog of {@link PriceRangeResolver}. For a type {@code T} the
 * range is the MIN..MAX of {@code retailNet} across all <b>active</b> finishing materials whose
 * {@code type} equals {@code T} and whose {@code retailNet} is non-null. Materials with a
 * {@code null} {@code retailNet} or that are inactive are excluded, and a type with no qualifying
 * material yields an empty range {@link PriceRange#EMPTY} — there is no fabricated fallback.
 *
 * <p>The key carries an optional {@code offerPackageId}:
 * <ul>
 *   <li>{@code offerPackageId == null} — <b>package-less</b>: fold across all packages (the widest
 *       honest band, used as the assignment default when there is no package context, R3.4).</li>
 *   <li>{@code offerPackageId != null} — <b>package-scoped</b>: restrict to materials whose
 *       {@code finishing_material_packages} membership contains that package (R12.5).</li>
 * </ul>
 *
 * <p>The resolver is a pure, total, deterministic function of its inputs: it performs no I/O, holds
 * no state, never persists anything, and always produces the same result for the same arguments. Any
 * {@code null} material, a material with a {@code null} {@code type}, or a {@code null}-id type is
 * skipped so the function is total over any collection. The folding logic lives in {@code static}
 * helpers so it is property-testable without Spring.
 */
@Component
public class FinishingPriceRangeResolver {

    /**
     * Grouping key: the finishing material type id, plus the optional offer package id.
     * A {@code null} {@code offerPackageId} denotes the package-less (cross-package) range.
     */
    public record Key(Long finishingMaterialTypeId, Long offerPackageId) {
    }

    /**
     * A computed MIN..MAX {@code retailNet} range. An empty range is {@code PriceRange(null, null)}.
     */
    public record PriceRange(BigDecimal min, BigDecimal max) {

        /** The empty range returned for a type with no qualifying material. */
        public static final PriceRange EMPTY = new PriceRange(null, null);
    }

    /**
     * Computes the price range for every finishing-material type reachable from {@code materials},
     * scoped by {@code offerPackageId}.
     *
     * <p>Only active materials with a non-null {@code retailNet} contribute; when
     * {@code offerPackageId != null}, a material additionally contributes only when its package
     * membership contains that package. Each qualifying material folds into its {@code type.id}
     * bucket, and the per-bucket MIN/MAX are folded via {@link BigDecimal#min}/{@link BigDecimal#max}.
     * Types with no qualifying material simply do not appear in the returned map (callers use
     * {@link #rangeFor} or a {@code getOrDefault(key, PriceRange.EMPTY)} for the empty-range
     * semantics).
     *
     * @param materials      the finishing materials to consider (may be {@code null} or empty)
     * @param offerPackageId the package scope, or {@code null} for the package-less fold
     * @return a map from {@link Key} (carrying {@code offerPackageId}) to its non-empty
     *         {@link PriceRange}
     */
    public Map<Key, PriceRange> compute(Collection<FinishingMaterialEntity> materials, Long offerPackageId) {
        return computeRanges(materials, offerPackageId);
    }

    /**
     * Returns the price range for a single {@code typeId} over {@code materials}, scoped by
     * {@code offerPackageId}.
     *
     * @param materials      the finishing materials to consider (may be {@code null} or empty)
     * @param typeId         the target finishing material type id
     * @param offerPackageId the package scope, or {@code null} for the package-less fold
     * @return the computed {@link PriceRange}, or {@link PriceRange#EMPTY} when no material qualifies
     */
    public PriceRange rangeFor(
            Collection<FinishingMaterialEntity> materials, Long typeId, Long offerPackageId) {
        return computeRanges(materials, offerPackageId)
                .getOrDefault(new Key(typeId, offerPackageId), PriceRange.EMPTY);
    }

    // --- pure static helpers (Spring-free, property-testable) --------------------------------

    private static Map<Key, PriceRange> computeRanges(
            Collection<FinishingMaterialEntity> materials, Long offerPackageId) {
        Map<Key, PriceRange> ranges = new HashMap<>();
        if (materials == null) {
            return ranges;
        }

        for (FinishingMaterialEntity material : materials) {
            if (!qualifies(material, offerPackageId)) {
                continue;
            }
            BigDecimal retailNet = material.getRetailNet();
            Long typeId = material.getType().getId();

            Key key = new Key(typeId, offerPackageId);
            ranges.merge(
                    key,
                    new PriceRange(retailNet, retailNet),
                    (existing, candidate) -> new PriceRange(
                            existing.min().min(candidate.min()),
                            existing.max().max(candidate.max())));
        }

        return ranges;
    }

    private static boolean qualifies(FinishingMaterialEntity material, Long offerPackageId) {
        if (material == null || !material.isActive() || material.getRetailNet() == null) {
            return false;
        }
        MaterialTypeEntity type = material.getType();
        if (type == null || type.getId() == null) {
            return false;
        }
        return offerPackageId == null || belongsToPackage(material, offerPackageId);
    }

    private static boolean belongsToPackage(FinishingMaterialEntity material, Long offerPackageId) {
        Collection<OfferPackageEntity> packages = material.getPackages();
        if (packages == null) {
            return false;
        }
        for (OfferPackageEntity pkg : packages) {
            if (pkg != null && offerPackageId.equals(pkg.getId())) {
                return true;
            }
        }
        return false;
    }
}
