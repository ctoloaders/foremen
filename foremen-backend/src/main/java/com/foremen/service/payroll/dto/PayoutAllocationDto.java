package com.foremen.service.payroll.dto;

import java.math.BigDecimal;

/**
 * FOR-07-06 (design §Controllers, {@code GET /api/worker-payouts/{id}/allocations}) — one allocation
 * of a payout onto a report line: how much of the payout went to which work line. Names use the
 * {@code ru → pl → code} fallback (the room's free-form label first).
 *
 * @param lineId       the {@code work_report_rooms} id
 * @param projectId    the line's project id
 * @param projectName  the line's project name
 * @param workItemName the localized work item name
 * @param roomName     the room label / localized room type name; {@code null} for a room-less line
 * @param amount       the allocated amount ({@code > 0})
 */
public record PayoutAllocationDto(
        Long lineId,
        Long projectId,
        String projectName,
        String workItemName,
        String roomName,
        BigDecimal amount) {
}
