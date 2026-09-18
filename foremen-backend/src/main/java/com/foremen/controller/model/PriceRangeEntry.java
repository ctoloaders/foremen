package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * A single computed price-range bucket for the pair ({@code offerPackageId},
 * {@code constructionMaterialTypeId}).
 *
 * <p>The range is the MIN..MAX of {@code retailNet} across all active construction materials of the
 * given type whose {@code packages} set contains the given package (see {@code PriceRangeResolver},
 * task 7.1). It is COMPUTED at read time and never persisted. An empty bucket (no active priced
 * material for the pair) carries a null {@code min} and null {@code max}.
 *
 * @param offerPackageId              the offer package this range is keyed to
 * @param constructionMaterialTypeId  the construction-material type this range is keyed to
 * @param min                         the minimum qualifying {@code retailNet} (null ⇒ empty range)
 * @param max                         the maximum qualifying {@code retailNet} (null ⇒ empty range)
 */
public record PriceRangeEntry(Long offerPackageId, Long constructionMaterialTypeId,
                              BigDecimal min, BigDecimal max) {}
