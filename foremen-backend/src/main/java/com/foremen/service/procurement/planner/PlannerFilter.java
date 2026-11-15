package com.foremen.service.procurement.planner;

import java.util.Set;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.planner.PlannerSliceRules.Slice;

/**
 * Filters of the purchase planner (FOR-11-01 R1.2, R2, R3.1). Every field is optional; all of them
 * compose with each other, with pagination and with the project scope.
 *
 * @param projectId      restrict to one project (still intersected with the caller's scope)
 * @param statuses       stages to keep ({@code status} repeated); null or empty means any
 * @param sourcingPath   sourcing path to keep
 * @param requestId      only positions of this purchase request
 * @param noRequest      only positions without a purchase request
 * @param buyer          {@code ALL} (default), {@code MINE} or {@code UNASSIGNED}
 * @param slice          quick slice; null or {@code ALL} applies none
 * @param urgent         only urgent positions
 * @param q              case-insensitive text over material model/SKU, producer, seller, request
 *                       reference and notes (wildcards are escaped)
 * @param hideDelivered  hide stage {@code RECEIVED} (FOR-11-00 R22)
 * @param proformaId     only positions on this proforma through an active line (FOR-11-03 R18.4)
 * @param externalOrderId only positions that have a line on this external order (FOR-11-03 R18.4)
 * @param noProforma     only positions without an active proforma line (FOR-11-03 R18.1)
 * @param noExternalOrder only positions without a line on a non-cancelled external order (FOR-11-03 R18.1)
 */
public record PlannerFilter(
        Long projectId,
        Set<PositionStatus> statuses,
        SourcingPath sourcingPath,
        Long requestId,
        boolean noRequest,
        BuyerMode buyer,
        Slice slice,
        boolean urgent,
        String q,
        boolean hideDelivered,
        Long proformaId,
        Long externalOrderId,
        boolean noProforma,
        boolean noExternalOrder) {

    /** The FOR-11-01 shape without the FOR-11-03 filters (additive extension, R29.4). */
    public PlannerFilter(Long projectId, Set<PositionStatus> statuses, SourcingPath sourcingPath, Long requestId,
                         boolean noRequest, BuyerMode buyer, Slice slice, boolean urgent, String q,
                         boolean hideDelivered) {
        this(projectId, statuses, sourcingPath, requestId, noRequest, buyer, slice, urgent, q, hideDelivered,
                null, null, false, false);
    }

    /** The shape before the no-proforma / no-order filters. */
    public PlannerFilter(Long projectId, Set<PositionStatus> statuses, SourcingPath sourcingPath, Long requestId,
                         boolean noRequest, BuyerMode buyer, Slice slice, boolean urgent, String q,
                         boolean hideDelivered, Long proformaId, Long externalOrderId) {
        this(projectId, statuses, sourcingPath, requestId, noRequest, buyer, slice, urgent, q, hideDelivered,
                proformaId, externalOrderId, false, false);
    }

    /** R1.5. */
    public enum BuyerMode { ALL, MINE, UNASSIGNED }

    /** A filter that keeps everything the caller may see. */
    public static PlannerFilter none() {
        return new PlannerFilter(null, null, null, null, false, BuyerMode.ALL, Slice.ALL, false, null, false, null, null,
                false, false);
    }

    /** The same filter without the slice, used for the slice counts (R2.6). */
    public PlannerFilter withoutSlice() {
        return new PlannerFilter(projectId, statuses, sourcingPath, requestId, noRequest, buyer,
                Slice.ALL, urgent, q, hideDelivered, proformaId, externalOrderId,
                noProforma, noExternalOrder);
    }
}
