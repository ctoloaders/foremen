package com.foremen.controller.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Update payload for a finishing material. Structurally identical to
 * {@link FinishingMaterialCreateRequest} — the entity has no {@code code} and no {@code name} to
 * protect, so every field (references, packages, free-text fields, prices, {@code link},
 * {@code photo}, {@code active}) is updatable. The same validation rules apply, and reference
 * existence is re-validated on the service write path.
 */
public record FinishingMaterialUpdateRequest(
    @NotNull Long categoryId,
    @NotNull Long materialId,
    Long typeId,
    Long producerId,
    @NotEmpty Set<Long> offerPackageIds,
    @NotNull Long unitId,
    @Size(max = 255) String model,
    @Size(max = 255) String sku,
    String features,
    @DecimalMin("0.00") @DecimalMax("9999999999.99") BigDecimal purchasePrice,
    @DecimalMin("0.00") @DecimalMax("9999999999.99") BigDecimal retailGross,
    @DecimalMin("0.00") @DecimalMax("9999999999.99") BigDecimal retailNet,
    @Size(max = 1024) String link,
    @Size(max = 512) String photo,
    Boolean active
) {}
