package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.dao.model.ConsumptionBranch;

/**
 * Response returned after updating a {@code WorkMaterialConsumption} norm. Mirrors
 * {@link WorkMaterialConsumptionDtoExtendedModel}: the localized {@link RefDto} references,
 * {@code branch}/{@code branchLabel}, the {@code materialType} analog group, the raw reference ids,
 * {@code normQty}/{@code wastePct}, the read-time {@code typeBatchRange} + analog {@code materials},
 * the single localized {@code justification} plus BOTH raw variants, and the citation.
 */
public record WorkMaterialConsumptionUpdateResponse(
        Long id,
        RefDto workItem,
        RefDto offerPackage,
        RefDto materialUnit,
        ConsumptionBranch branch,
        String branchLabel,
        RefDto materialType,
        Long workItemId,
        Long materialUnitId,
        Long constructionMaterialTypeId,
        Long finishingMaterialTypeId,
        BigDecimal normQty,
        BigDecimal wastePct,
        MoneyRangeDto typeBatchRange,
        List<AnalogMaterialDto> materials,
        String justification,
        String justificationRU,
        String justificationPL,
        String sourceType,
        String sourceDoc,
        String sourceUrl,
        String sourceRef
) {}
