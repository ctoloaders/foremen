package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;

/**
 * Computes the <b>price range (вилка цен)</b> for construction materials, keyed by
 * {@link ConstructionMaterialTypeEntity} only (FOR-05-04-UI, Requirement 5.3, 5.7).
 *
 * <p>After the material-side package collapse the range is no longer fanned out per offer package.
 * For a type {@code T} the range is the MIN..MAX of {@code retailNet} across all <b>active</b>
 * construction materials whose {@code type} equals {@code T} and whose {@code retailNet} is
 * non-null. Materials with a {@code null} {@code retailNet} or that are inactive are excluded, and a
 * type with no qualifying material yields an empty range {@code PriceRange(null, null)} — there is
 * no fabricated fallback (Requirement 5.7).
 *
 * <p>The resolver is a pure, total, deterministic function of its input {@code Collection}: it
 * performs no I/O, holds no state, never persists anything, and always produces the same result for
 * the same set of materials. Any {@code null} material, a material with a {@code null} {@code type},
 * or a {@code null}-id type is skipped so the function is total over any collection.
 */
@Component
public class PriceRangeResolver {

    /** Grouping key: the construction material type id. */
    public record PriceRangeKey(Long constructionMaterialTypeId) {
    }

    /**
     * A computed MIN..MAX {@code retailNet} range. An empty range is {@code PriceRange(null, null)}.
     */
    public record PriceRange(BigDecimal min, BigDecimal max) {

        /** The empty range returned for a type with no qualifying material. */
        public static final PriceRange EMPTY = new PriceRange(null, null);
    }

    /**
     * Computes the price range for every construction-material type reachable from {@code materials}.
     *
     * <p>Only active materials with a non-null {@code retailNet} contribute; each such material folds
     * into its {@code type.id} bucket, and the per-bucket MIN/MAX are folded via
     * {@link BigDecimal#min}/{@link BigDecimal#max}. Types with no qualifying material simply do not
     * appear in the returned map (callers use {@link #rangeFor} or a
     * {@code getOrDefault(key, PriceRange.EMPTY)} for the empty-range semantics).
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

            PriceRangeKey key = new PriceRangeKey(typeId);
            ranges.merge(
                    key,
                    new PriceRange(retailNet, retailNet),
                    (existing, candidate) -> new PriceRange(
                            existing.min().min(candidate.min()),
                            existing.max().max(candidate.max())));
        }

        return ranges;
    }

    /**
     * Returns the price range for a single {@code typeId} over {@code materials}.
     *
     * @param materials the construction materials to consider (may be {@code null} or empty)
     * @param typeId    the target construction material type id
     * @return the computed {@link PriceRange}, or {@link PriceRange#EMPTY} when no material qualifies
     */
    public PriceRange rangeFor(Collection<ConstructionMaterialEntity> materials, Long typeId) {
        return compute(materials)
                .getOrDefault(new PriceRangeKey(typeId), PriceRange.EMPTY);
    }

    private boolean qualifies(ConstructionMaterialEntity material) {
        if (material == null || !material.isActive() || material.getRetailNet() == null) {
            return false;
        }
        ConstructionMaterialTypeEntity type = material.getType();
        return type != null && type.getId() != null;
    }
}
