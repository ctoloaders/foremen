package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * A single computed price-range bucket keyed by {@code constructionMaterialTypeId}.
 *
 * <p>The range is the MIN..MAX of {@code retailNet} across all active construction materials of the
 * given type (see {@code PriceRangeResolver}, FOR-05-04-UI Requirement 5.3, 5.7). It is COMPUTED at
 * read time and never persisted. An empty bucket (no active priced material for the type) carries a
 * null {@code min} and null {@code max}.
 *
 * @param constructionMaterialTypeId  the construction-material type this range is keyed to
 * @param min                         the minimum qualifying {@code retailNet} (null ⇒ empty range)
 * @param max                         the maximum qualifying {@code retailNet} (null ⇒ empty range)
 */
public record PriceRangeEntry(Long constructionMaterialTypeId,
                              BigDecimal min, BigDecimal max) {}
