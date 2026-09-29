package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Create payload for an {@code AssortmentGroup} (FOR-05-04, Requirement 6.1; room-types added in
 * FOR-05-05 Amendment A1, point B).
 *
 * <p>{@code roomTypeIds} is optional (nullable/empty allowed): it declares the room TYPES this
 * group's materials apply to. Null or empty means the group applies to no rooms in the apply-merge,
 * consistent with A1's explicit-join semantics.
 */
public record AssortmentGroupCreateRequest(
    @NotBlank String nameRU,
    @NotBlank String namePL,
    @NotNull Integer sortOrder,
    @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal referenceQty,
    @NotNull @Pattern(regexp = "szt|m2") String referenceUnit,
    List<Long> roomTypeIds
) {}
