package com.foremen.controller.model;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Edit-form DTO for an {@code AssortmentGroup} (FOR-05-04, Requirement 6.1). Carries the raw
 * i18n fields the edit form pre-selects, mirroring {@code WorkVolumeFormulaDtoExtendedModel},
 * plus the group's single {@code referenceQty} ({@literal >} 0) and {@code referenceUnit}
 * ({@code 'szt'} or {@code 'm2'}) (FOR-05-04-UI).
 */
public record AssortmentGroupDtoExtendedModel(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull Integer sortOrder,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal referenceQty,
    @NotNull @Pattern(regexp = "szt|m2") String referenceUnit
) {}
