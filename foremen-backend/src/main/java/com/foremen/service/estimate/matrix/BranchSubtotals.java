package com.foremen.service.estimate.matrix;

/**
 * Per-branch subtotals for a {@link WorkTypeGroupDto} or the {@link EstimateMatrixDto} header
 * (FOR-05-05, R2.2, R14.1). Each subtotal is a {@link MoneyRange} that collapses (min == max) when
 * every contributing material line of that branch is concrete. Mirrors the frontend
 * {@code BranchSubtotals}.
 *
 * @param works        the labour subtotal (always a collapsed point — labour has no range)
 * @param construction the construction-materials subtotal range
 * @param finishing    the finishing-materials subtotal range
 */
public record BranchSubtotals(MoneyRange works, MoneyRange construction, MoneyRange finishing) {

    /** The zero subtotals — the neutral value for an empty group / a line-less estimate. */
    public static final BranchSubtotals ZERO =
            new BranchSubtotals(MoneyRange.ZERO, MoneyRange.ZERO, MoneyRange.ZERO);
}
