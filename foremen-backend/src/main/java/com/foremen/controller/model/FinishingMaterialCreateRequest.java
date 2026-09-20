package com.foremen.controller.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Create payload for a finishing material (one concrete finishing offer).
 *
 * <p>There is no {@code code} and no {@code name}. {@code categoryId}, {@code materialId}, and
 * {@code unitId} are mandatory references; {@code typeId}/{@code producerId} are optional;
 * {@code offerPackageIds} must contain at least one package. The free-text {@code model}/{@code sku}
 * are capped at 255 characters and {@code features} is unbounded text. The three prices
 * ({@code purchasePrice}/{@code retailGross}/{@code retailNet}) are optional but, when present, must
 * fall within {@code 0.00}..{@code 9,999,999,999.99}. {@code link} is an optional product URL (max
 * 1024). {@code photo} is an optional ImageStorage object key (not a CDN URL). {@code active}
 * defaults to {@code true} when omitted. Bean-validation covers the structural rules; existence of
 * every referenced row is validated on the service write path.
 */
public record FinishingMaterialCreateRequest(
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
