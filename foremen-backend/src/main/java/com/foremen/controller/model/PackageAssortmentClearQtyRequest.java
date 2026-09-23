package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body for {@code POST /api/assortment-positions/package-clear-qty} (FOR-05-04-UI): clears ONE
 * band's quantity override for a position under the query-param package. Only {@code null} means
 * "not overridden" (with {@code 0} now a legitimate stored override), so a clear cannot be
 * expressed through the batched save and uses this dedicated endpoint instead.
 *
 * @param positionId the assortment position whose band override to clear
 * @param band       which band's qty override to null: {@code min} / {@code avg} / {@code max}
 */
public record PackageAssortmentClearQtyRequest(
        @NotNull Long positionId,
        @NotNull @Pattern(regexp = "min|avg|max") String band) {
}
