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
 * Read-path service model for a {@code WorkMaterialConsumption} norm (FOR-04-19).
 *
 * <p>Carries the resolved, localized references as {@link RefDto} objects
 * ({@code workItem}/{@code offerPackage}/{@code materialUnit}) plus the two nullable analog-group
 * type references ({@code constructionMaterialType}/{@code finishingMaterialType}), exactly one of
 * which is non-null; the controller mapper picks the set one into the DTO's single
 * {@code materialType}. {@code branch} is the raw enum and {@code branchLabel} its localized display
 * label. {@code justification} is the single localized value (PL fallback, populated by the i18n
 * framework from {@code justificationRU}/{@code justificationPL}); both raw variants are retained for
 * the extended (edit) DTO.
 *
 * <p>The read-time computed {@code typeBatchRange} (type-level money band) and the analog
 * {@code materials} list are NOT resolved here — they are populated later by the
 * {@code MaterialRangeResolver}/{@code MaterialBatchLookup} (tasks 4.x). Mutable ({@code @Data}) so
 * the service can stamp the localized references, branch label and computed drill-in fields after
 * loading.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkMaterialConsumptionServiceModel {
    private Long id;
    private RefDto workItem;
    private RefDto offerPackage;
    private RefDto materialUnit;
    private ConsumptionBranch branch;
    private String branchLabel;
    private RefDto constructionMaterialType;
    private RefDto finishingMaterialType;
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
