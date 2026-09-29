package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Update payload for an {@code AssortmentGroup} (FOR-05-04, Requirement 6.1; room-types added in
 * FOR-05-05 Amendment A1, point B).
 *
 * <p>{@code roomTypeIds} is the FULL desired set of room TYPES this group's materials apply to;
 * the service REPLACES the group's room-type association with this set. Null or empty clears the
 * association (group applies to no rooms in the apply-merge).
 */
public record AssortmentGroupUpdateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull Integer sortOrder,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal referenceQty,
    @NotNull @Pattern(regexp = "szt|m2") String referenceUnit,
    List<Long> roomTypeIds
) {}
