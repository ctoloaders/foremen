package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response of {@code POST /api/purchase-positions/selection-total} (FOR-11-01 R6.2).
 *
 * @param count          number of counted positions (priced and unpriced)
 * @param unpriced       how many of the counted positions have no sale price (they add nothing to the totals)
 * @param projectId      the shared project of the counted positions; {@code null} when none or mixed
 * @param mixedProjects  true when the counted positions belong to more than one project
 * @param vatBreakdown   sale totals per sale-VAT rate (rate ascending, unknown rate first)
 * @param skipped        positions that were not counted, with the reason
 */
public record SelectionTotalResponse(
        int count,
        int unpriced,
        BigDecimal saleNet,
        BigDecimal vat,
        BigDecimal saleGross,
        List<VatBreakdownItem> vatBreakdown,
        Long projectId,
        boolean mixedProjects,
        List<SkippedPosition> skipped
) {

    /** Sale totals of the counted positions with one sale-VAT rate ({@code ratePercent} null = no rate). */
    public record VatBreakdownItem(BigDecimal ratePercent, BigDecimal net, BigDecimal vat, BigDecimal gross) {}

    /** A position that is not part of the total. */
    public record SkippedPosition(Long positionId, SkipReason reason) {}

    /** Why a position is not counted (R6.3). */
    public enum SkipReason { NOT_ACCEPTED, NOT_AGREED, NO_SALE_PRICE, NOT_FOUND }
}
