package com.foremen.controller.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Update payload for a construction material. Structurally identical to
 * {@link ConstructionMaterialCreateRequest} — the entity has no {@code code} to protect, so every
 * field (localized {@code name}, references, packages, prices, {@code website}, {@code image},
 * {@code active}) is updatable. The same validation rules apply, and reference existence is
 * re-validated on the service write path.
 */
public record ConstructionMaterialUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull Long typeId,
    Long producerId,
    Long sellerId,
    @NotEmpty Set<Long> offerPackageIds,
    @NotNull Long unitId,
    @NotNull Long currencyId,
    @DecimalMin("0.00") @DecimalMax("9999999999.99") BigDecimal purchasePrice,
    @DecimalMin("0.00") @DecimalMax("9999999999.99") BigDecimal retailGross,
    @DecimalMin("0.00") @DecimalMax("9999999999.99") BigDecimal retailNet,
    @Size(max = 255) String website,
    @Size(max = 512) String image,
    Boolean active
) {}
