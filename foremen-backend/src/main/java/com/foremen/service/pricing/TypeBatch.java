package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * The <b>analog batch</b> for one {@code (material type, branch)} pair (FOR-04-19, Requirement 4;
 * package-less per FOR-05-04 Requirement 7.2): the non-null {@code retailNet}s of every ACTIVE
 * material of that type, across all offer packages.
 *
 * <p>It is produced by {@link MaterialBatchLookup} (which owns the DB read) so that
 * {@code MaterialRangeResolver} can stay a pure, deterministic function of already-loaded data. The
 * {@code retailNets} list excludes materials with a {@code null} {@code retailNet}
 * (Requirement 4.6); an EMPTY list therefore means the type has no priced material at all, which the
 * resolver turns into a batch range of {@code 0..0} (Requirement 4.2). Prices from the construction
 * branch ({@code construction_materials}) and the finishing branch ({@code finishing_materials}) are
 * kept apart by the {@code branch} discriminator so a construction type id and a finishing type id
 * never collide.
 *
 * @param materialTypeId  the analog-group material type id (a {@code CONSTRUCTION_MATERIAL_TYPES} id
 *                        for {@link ConsumptionBranch#construction}, a {@code MATERIAL_TYPES} id for
 *                        {@link ConsumptionBranch#finishing})
 * @param branch          which material catalog the batch was drawn from
 * @param retailNets      the non-null {@code retailNet}s of the batch's materials (never {@code null};
 *                        empty when the type has no priced material)
 */
public record TypeBatch(Long materialTypeId,
                        ConsumptionBranch branch,
                        List<BigDecimal> retailNets) {
}
