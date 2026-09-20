package com.foremen.controller.model;

import com.foremen.dao.model.ConsumptionBranch;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Create payload for a {@code WorkMaterialConsumption} norm (FOR-04-19) — one
 * {@code (work item, offer package, material TYPE)} consumption norm.
 *
 * <p>There is no {@code code} and no {@code name}. {@code workItemId}, {@code offerPackageId},
 * {@code branch}, and {@code materialUnitId} are mandatory references; exactly ONE material-type id
 * ({@code constructionMaterialTypeId} XOR {@code finishingMaterialTypeId}) must be set and must match
 * {@code branch} — this XOR + branch-match rule and reference existence are validated on the service
 * write path (task 3.2), not by bean validation. {@code normQty} (material-unit per one work-unit) is
 * mandatory and must fall within {@code [0.0000, 99999999.9999]}. {@code wastePct} is optional and,
 * when present, must fall within {@code [0.00, 999.99]}. {@code justificationRU}/{@code justificationPL}
 * are optional free text (the only i18n owned by the entity). The citation {@code sourceType}/
 * {@code sourceDoc}/{@code sourceRef} are mandatory non-blank; {@code sourceUrl} is optional (max 1024).
 *
 * @param workItemId                 mandatory work-item reference
 * @param offerPackageId             mandatory offer-package reference
 * @param branch                     mandatory branch (construction / finishing)
 * @param materialUnitId             mandatory numerator material-unit reference
 * @param constructionMaterialTypeId construction analog-group type (set iff branch == construction)
 * @param finishingMaterialTypeId    finishing analog-group type (set iff branch == finishing)
 * @param normQty                    consumption norm, material-unit per work-unit, in {@code [0, 99999999.9999]}
 * @param wastePct                   optional waste percentage in {@code [0.00, 999.99]}
 * @param justificationRU           optional RU justification text
 * @param justificationPL           optional PL justification text
 * @param sourceType                 mandatory citation source type
 * @param sourceDoc                  mandatory citation source document
 * @param sourceUrl                  optional citation source URL (max 1024)
 * @param sourceRef                  mandatory citation source reference
 */
public record WorkMaterialConsumptionCreateRequest(
    @NotNull Long workItemId,
    @NotNull Long offerPackageId,
    @NotNull ConsumptionBranch branch,
    @NotNull Long materialUnitId,
    Long constructionMaterialTypeId,
    Long finishingMaterialTypeId,
    @NotNull @DecimalMin("0.0000") @DecimalMax("99999999.9999") BigDecimal normQty,
    @DecimalMin("0.00") @DecimalMax("999.99") BigDecimal wastePct,
    String justificationRU,
    String justificationPL,
    @NotBlank String sourceType,
    @NotBlank String sourceDoc,
    @Size(max = 1024) String sourceUrl,
    @NotBlank String sourceRef
) {}
