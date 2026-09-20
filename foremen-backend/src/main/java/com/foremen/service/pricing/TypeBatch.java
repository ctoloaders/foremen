package com.foremen.service.pricing;

import com.foremen.dao.model.ConsumptionBranch;

import java.math.BigDecimal;
import java.util.List;

/**
 * The <b>analog batch</b> for one {@code (offer package, material type, branch)} triple (FOR-04-19,
 * Requirement 4): the non-null {@code retailNet}s of every ACTIVE material of that type whose
 * {@code packages} set contains the offer package.
 *
 * <p>It is produced by {@link MaterialBatchLookup} (which owns the DB read) so that
 * {@code MaterialRangeResolver} can stay a pure, deterministic function of already-loaded data. The
 * {@code retailNets} list excludes materials with a {@code null} {@code retailNet}
 * (Requirement 4.6); an EMPTY list therefore means the type has no priced material in the package,
 * which the resolver turns into a batch range of {@code 0..0} (Requirement 4.2). Prices from the
 * construction branch ({@code construction_materials} ⋈ {@code construction_material_packages}) and
 * the finishing branch ({@code finishing_materials} ⋈ {@code finishing_material_packages}) are kept
 * apart by the {@code branch} discriminator so a construction type id and a finishing type id never
 * collide.
 *
 * @param offerPackageId  the offer package the batch belongs to
 * @param materialTypeId  the analog-group material type id (a {@code CONSTRUCTION_MATERIAL_TYPES} id
 *                        for {@link ConsumptionBranch#construction}, a {@code MATERIAL_TYPES} id for
 *                        {@link ConsumptionBranch#finishing})
 * @param branch          which material catalog the batch was drawn from
 * @param retailNets      the non-null {@code retailNet}s of the batch's materials (never {@code null};
 *                        empty when the type has no priced material in the package)
 */
public record TypeBatch(Long offerPackageId,
                        Long materialTypeId,
                        ConsumptionBranch branch,
                        List<BigDecimal> retailNets) {
}
