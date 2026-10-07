package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;

import com.foremen.dao.model.ConsumptionBasis;
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
 * @param normUnit             the material NORM's unit code (e.g. {@code l}, {@code m2}, {@code szt})
 *                             so the UI can render the norm quantity (#8), or {@code null} when the
 *                             unit cannot be resolved at read time
 * @param quantity             the RESOLVED physical quantity actually used for this line (FOR-05-05
 *                             amendments #1/#4): {@code manualQty} when the line is overridden, else
 *                             {@code norm} for a {@code PER_ROOM} basis, else {@code norm × Volume}
 *                             for {@code PER_UNIT}. This is the physical quantity the money
 *                             contribution multiplies by
 * @param quantityOverridden   {@code true} iff {@code quantity} is a manual per-line override (#1),
 *                             which wins over the consumption basis
 * @param consumptionBasis     the line's copied consumption basis (#4): {@code PER_UNIT}
 *                             ({@code norm × Volume}) or {@code PER_ROOM} ({@code norm}, once per
 *                             assigned room). Serialized by its enum name; the frontend mirrors it as
 *                             {@code 'PER_UNIT' | 'PER_ROOM'}
 * @param appliedFromPackage   {@code true} iff this line was placed by an applied offer package
 *                             (FOR-05-05 Amendment A1)
 * @param chosenProductPackageName a representative localized package name of the chosen finishing
 *                             product, present ONLY when {@code chosenPackageDiffersFromApplied} is
 *                             {@code true}; {@code null} otherwise (and {@code null} for construction
 *                             lines, Placeholders, and when the estimate has no applied package)
 * @param chosenPackageDiffersFromApplied {@code true} when the line's chosen concrete FINISHING
 *                             product is NOT in the estimate's applied package (compared by package
 *                             code); {@code false} for construction lines, Placeholders, and when the
 *                             estimate has no applied package
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
        BigDecimal concreteNet,
        String normUnit,
        BigDecimal quantity,
        boolean quantityOverridden,
        ConsumptionBasis consumptionBasis,
        boolean appliedFromPackage,
        String chosenProductPackageName,
        boolean chosenPackageDiffersFromApplied) {

    /** Whether this line has a chosen concrete product (⇒ its contribution is a point, R6.4). */
    public boolean isConcrete() {
        return concreteMaterialId != null;
    }

    /** Whether this line was placed by an applied offer package (FOR-05-05 Amendment A1). */
    public boolean isAppliedFromPackage() {
        return appliedFromPackage;
    }
}
