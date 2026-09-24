package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * One copied material line of a cell (FOR-05-05, design §B6), keyed by {@code (branch, material type)}
 * within the assignment (R13.4). Carries the copied {@code Type_Price_Range}
 * ({@code rangeMin}..{@code rangeMax}) per one work-unit and, when a concrete product is chosen, its
 * {@code concreteNet} (which collapses the line to a point, R6.4). A line with a {@code null}
 * {@code concreteMaterialId} is a Placeholder (R6.6). Mirrors the frontend {@code MaterialLineDto}.
 *
 * @param id                   the material line id
 * @param branch               the consumption branch this line belongs to
 * @param typeId               the material type id (construction or finishing, per {@code branch})
 * @param typeName             the material type display name (localized at the read layer)
 * @param norm                 the copied consumption norm per one work-unit (R4.2)
 * @param rangeMin             the copied {@code Type_Price_Range} min (per one work-unit)
 * @param rangeMax             the copied {@code Type_Price_Range} max (per one work-unit)
 * @param concreteMaterialId   the chosen concrete product id, or {@code null} for a Placeholder
 * @param concreteMaterialName the chosen concrete product name, or {@code null} for a Placeholder
 * @param concreteNet          the copied chosen product {@code retailNet}, or {@code null}
 */
public record MaterialLineDto(
        Long id,
        ConsumptionBranch branch,
        Long typeId,
        String typeName,
        BigDecimal norm,
        BigDecimal rangeMin,
        BigDecimal rangeMax,
        Long concreteMaterialId,
        String concreteMaterialName,
        BigDecimal concreteNet) {

    /** Whether this line has a chosen concrete product (⇒ its contribution is a point, R6.4). */
    public boolean isConcrete() {
        return concreteMaterialId != null;
    }
}
