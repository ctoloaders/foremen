package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Save payload for the single-package grouped assortment editor (FOR-05-04-UI): the edited groups
 * (each with its {@code referenceQty}/{@code referenceUnit}) and, nested under each group, the
 * edited positions (min/avg/max price). The target package is supplied as a request parameter,
 * not in the body. For each position, this package's price row is upserted (created if absent,
 * else updated); positions are global, so their existence is managed via the position CRUD, not
 * here.
 *
 * <p>Validation: {@code referenceQty > 0}, {@code referenceUnit ∈ {szt, m2}}, and every price
 * {@code >= 0}.
 *
 * @param groups the edited groups (non-empty)
 */
public record PackageAssortmentSaveRequest(
        @NotEmpty @Valid List<Group> groups) {

    /**
     * An edited assortment group: its id plus the new reference quantity/unit, and the group's
     * edited positions.
     */
    public record Group(
            @NotNull Long groupId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal referenceQty,
            @NotNull @Pattern(regexp = "szt|m2") String referenceUnit,
            @Valid List<Position> positions) {
    }

    /**
     * An edited position: its id, the new min/avg/max prices (each {@code >= 0}) for the target
     * package, and the OPTIONAL per-band quantity overrides ({@code minQty}/{@code avgQty}/
     * {@code maxQty} — each {@code >= 0} when present; {@code 0} is a legitimate override that makes
     * the band contribute nothing). Only {@code null} means "not overridden" — the band then falls
     * back to the group's {@code referenceQty}. A qty is cleared to {@code null} via the dedicated
     * {@code /package-clear-qty} endpoint, NOT through this batched save.
     */
    public record Position(
            @NotNull Long positionId,
            @DecimalMin("0") BigDecimal minPrice,
            @DecimalMin("0") BigDecimal avgPrice,
            @DecimalMin("0") BigDecimal maxPrice,
            @DecimalMin("0") BigDecimal minQty,
            @DecimalMin("0") BigDecimal avgQty,
            @DecimalMin("0") BigDecimal maxQty) {
    }
}
