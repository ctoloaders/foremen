package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * FOR-05-05 additional task 3 — the outcome of a committed Rooms-tab save.
 *
 * @param created          ids of the created rooms, in request order
 * @param updated          ids of the updated rooms
 * @param deleted          ids of the deleted rooms
 * @param estimateChanged  whether the save re-derived (changed) the estimate
 * @param estimateChanges  the number of estimate cells the save changed
 * @param totalNetBefore   the estimate net total before the save ({@code null} when no estimate)
 * @param totalNetAfter    the estimate net total after the save ({@code null} when no estimate)
 */
public record RoomsSaveResponse(
        List<Long> created,
        List<Long> updated,
        List<Long> deleted,
        boolean estimateChanged,
        int estimateChanges,
        BigDecimal totalNetBefore,
        BigDecimal totalNetAfter) {
}
