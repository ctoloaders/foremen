package com.foremen.controller.model;

import com.foremen.dao.model.ConsumptionBranch;

import java.math.BigDecimal;
import java.util.List;

/**
 * List/read DTO for a {@code WorkMaterialConsumption} norm (FOR-04-19) — one
 * {@code (work item, offer package, material TYPE)} row.
 *
 * <p>References are exposed as localized {@link RefDto} objects ({@code workItem}/{@code offerPackage}/
 * {@code materialUnit}). {@code branch} is the raw enum and {@code branchLabel} its localized display
 * label. {@code materialType} is the analog GROUP — whichever of the construction/finishing type
 * references is set (exactly one). {@code normQty} is the consumption norm (material-unit per one
 * work-unit) and {@code wastePct} the optional waste percentage.
 *
 * <p>The read-time computed drill-in fields: {@code typeBatchRange} is the type-level money band
 * {@code normQty × [MIN..MAX retailNet]} over the type's active priced materials in the package
 * (explicit {@code 0..0} when the batch is empty), and {@code materials} is the analog batch —
 * every concrete material of the type in the package with its per-material money cost. There is no
 * {@code code} and no {@code name}; {@code justification} is the single localized value (PL fallback:
 * {@code ru}→{@code justificationRU}, else {@code justificationPL}). The citation fields
 * {@code sourceType}/{@code sourceDoc}/{@code sourceUrl}/{@code sourceRef} carry the norm's provenance.
 */
public record WorkMaterialConsumptionDtoModel(
        Long id,
        RefDto workItem,
        RefDto offerPackage,
        RefDto materialUnit,
        ConsumptionBranch branch,
        String branchLabel,
        RefDto materialType,
        BigDecimal normQty,
        BigDecimal wastePct,
        MoneyRangeDto typeBatchRange,
        List<AnalogMaterialDto> materials,
        String justification,
        String sourceType,
        String sourceDoc,
        String sourceUrl,
        String sourceRef
) {}
