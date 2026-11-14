package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Update payload for a Project_Material. {@code projectId} is optional and, when supplied, must equal
 * the stored project (the project is immutable: a different value is rejected with 409
 * {@code error.projectMaterial.project.immutable}).
 */
public record ProjectMaterialUpdateRequest(
    Long projectId,
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
