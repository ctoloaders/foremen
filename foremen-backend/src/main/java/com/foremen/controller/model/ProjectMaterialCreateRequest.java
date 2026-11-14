package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create payload for a Project_Material (FOR-11-00 R3): the finishing-material fields plus the
 * mandatory owning {@code projectId} (must be an ACTIVE project) and the optional {@code sellerId}.
 */
public record ProjectMaterialCreateRequest(
    @NotNull Long projectId,
    Long sellerId,
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
    @DecimalMin("0.00") @DecimalMax("9999999999.99") BigDecimal costNet,
    @Size(max = 1024) String link,
    @Size(max = 512) String photo,
    Boolean active
) {}
