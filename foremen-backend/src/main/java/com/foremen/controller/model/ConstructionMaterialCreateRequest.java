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
 * Create payload for a construction material (one concrete "this material, at this seller, at this
 * price" offer).
 *
 * <p>There is no {@code code}. The localized {@code name} is mandatory ({@code nameRU}/{@code namePL}
 * non-blank). {@code typeId}, {@code unitId}, and {@code currencyId} are mandatory references;
 * {@code producerId}/{@code sellerId} are optional; {@code offerPackageIds} must contain at least one
 * package. The three prices are optional but, when present, must fall within
 * {@code 0.00}..{@code 9,999,999,999.99}. {@code image} is an optional ImageStorage object key (not a
 * CDN URL). {@code active} defaults to {@code true} when omitted. Bean-validation covers the
 * structural rules; existence of every referenced row is validated on the service write path.
 */
public record ConstructionMaterialCreateRequest(
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
