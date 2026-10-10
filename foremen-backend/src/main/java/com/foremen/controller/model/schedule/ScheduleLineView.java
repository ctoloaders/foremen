package com.foremen.controller.model.schedule;

import java.math.BigDecimal;

/**
 * One estimate line shown under an expanded {@code Schedule_Row} in the planning Gantt
 * (FOR-05-10 Requirement 5). A row groups all estimate lines of a single {@code WorkCategory};
 * each such line is projected to this flat view so the Harmonogram tab can render the row's
 * breakdown (work item, quantity, unit) without extra lookups.
 *
 * <p>This model never carries a man-days figure or the internal {@code Daily_Output_Rate}
 * (Requirement 5.4, 9.3): the per-line monetary value lives only on the row's
 * {@link ScheduleRowView#categoryValue()} aggregate (and only for a Money_Viewer), not here.
 *
 * @param estimateLineId the estimate line id
 * @param workItemId     the referenced work item id
 * @param name           the localized work item name (ru for ru requests, pl otherwise, falling
 *                       back to the code — Requirement 5.7)
 * @param quantity       the estimate line quantity
 * @param unit           the localized unit label of the work item
 */
public record ScheduleLineView(
        Long estimateLineId,
        Long workItemId,
        String name,
        BigDecimal quantity,
        String unit
) {}
