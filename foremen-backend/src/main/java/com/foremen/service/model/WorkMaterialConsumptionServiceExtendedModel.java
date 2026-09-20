package com.foremen.service.model;

import com.foremen.controller.model.AnalogMaterialDto;
import com.foremen.controller.model.MoneyRangeDto;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.model.ConsumptionBranch;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-path (and extended-read) service model for a {@code WorkMaterialConsumption} norm
 * (FOR-04-19).
 *
 * <p>Carries the raw reference ids the write path resolves/validates and the edit form pre-selects
 * ({@code workItemId}/{@code offerPackageId}/{@code materialUnitId}/{@code constructionMaterialTypeId}/
 * {@code finishingMaterialTypeId}), the {@code branch}, {@code normQty}/{@code wastePct}, BOTH raw
 * {@code justificationRU}/{@code justificationPL} variants (the only i18n owned by the entity), and
 * the citation. The resolved localized {@link RefDto} references, the {@code branchLabel}, the single
 * localized {@code justification}, and the read-time {@code typeBatchRange}/{@code materials} are
 * read-only concerns populated on the extended-read path only. Mutable ({@code @Data}) so the
 * {@code WorkMaterialConsumptionService} normalize step (task 3.2) can resolve/validate references and
 * enforce the XOR + branch-match rule before the entity is created/updated.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkMaterialConsumptionServiceExtendedModel {
    private Long id;
    private RefDto workItem;
    private RefDto offerPackage;
    private RefDto materialUnit;
    private ConsumptionBranch branch;
    private String branchLabel;
    private RefDto constructionMaterialType;
    private RefDto finishingMaterialType;
    private Long workItemId;
    private Long offerPackageId;
    private Long materialUnitId;
    private Long constructionMaterialTypeId;
    private Long finishingMaterialTypeId;
    private BigDecimal normQty;
    private BigDecimal wastePct;
    private MoneyRangeDto typeBatchRange;
    private List<AnalogMaterialDto> materials = new ArrayList<>();
    private String justification;
    private String justificationRU;
    private String justificationPL;
    private String sourceType;
    private String sourceDoc;
    private String sourceUrl;
    private String sourceRef;
}
