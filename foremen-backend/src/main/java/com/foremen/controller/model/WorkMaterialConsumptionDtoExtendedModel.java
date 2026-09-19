package com.foremen.controller.model;

import com.foremen.dao.model.ConsumptionBranch;

import java.math.BigDecimal;
import java.util.List;

/**
 * Extended DTO for a {@code WorkMaterialConsumption} norm (drives the edit form; also the single-read
 * and create/update response payload shape).
 *
 * <p>Exposes everything in {@link WorkMaterialConsumptionDtoModel} — the localized {@link RefDto}
 * references, {@code branch}/{@code branchLabel}, the {@code materialType} analog group, {@code normQty}/
 * {@code wastePct}, the read-time {@code typeBatchRange} + analog {@code materials}, the single
 * localized {@code justification}, and the citation — PLUS the raw reference ids the edit form needs
 * to pre-select ({@code workItemId}/{@code offerPackageId}/{@code materialUnitId}/
 * {@code constructionMaterialTypeId}/{@code finishingMaterialTypeId}) and BOTH raw
 * {@code justificationRU}/{@code justificationPL} variants for editing (Requirement 3.8).
 */
public record WorkMaterialConsumptionDtoExtendedModel(
        Long id,
        RefDto workItem,
        RefDto offerPackage,
        RefDto materialUnit,
        ConsumptionBranch branch,
        String branchLabel,
        RefDto materialType,
        Long workItemId,
        Long offerPackageId,
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
