package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code PUT /api/work-items/{id}/aggregate} (FOR-04-11 bug-report Round 2, item 1):
 * every pending edit of the work edit page applied in ONE transaction. Each part is optional;
 * {@code null} means "unchanged".
 *
 * @param workItem         base attributes (name, category, unit, active) or {@code null}
 * @param formula          default volume formula; a blank {@code sourceText} deletes it
 * @param packageOverrides per-package membership + override formula rows to upsert; a row that
 *                         becomes a non-member without override text is removed
 * @param roomTypeIds      full replacement of the room-type attachment ({@code []} clears it)
 * @param price            labour price; a {@code null} {@code netPrice} deletes the price row
 * @param consumptions     consumption-norm changes (create when {@code id == null}, update, or delete)
 * @param relatedWorkIds   full replacement of the related works ({@code []} clears them); {@code null} = unchanged
 */
public record WorkItemAggregateSaveRequest(
    @Valid WorkItemUpdateRequest workItem,
    @Valid FormulaChange formula,
    @Valid List<PackageOverrideChange> packageOverrides,
    List<Long> roomTypeIds,
    @Valid PriceChange price,
    @Valid List<ConsumptionChange> consumptions,
    List<Long> relatedWorkIds
) {
    /** Default formula change; blank/null text removes the formula. */
    public record FormulaChange(String sourceText) {}

    /** One offer-package row. */
    public record PackageOverrideChange(
        @NotNull Long offerPackageId,
        @NotNull Boolean member,
        String overrideSourceText
    ) {}

    /** Labour price; {@code netPrice == null} removes the price row. */
    public record PriceChange(Long currencyId, @Positive BigDecimal netPrice) {}

    /** One consumption norm: create ({@code id == null}), update, or {@code delete}. */
    public record ConsumptionChange(
        Long id,
        boolean delete,
        @Valid WorkMaterialConsumptionUpdateRequest data
    ) {}
}
