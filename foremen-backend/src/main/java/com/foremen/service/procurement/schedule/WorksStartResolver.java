package com.foremen.service.procurement.schedule;

import java.time.LocalDate;

/**
 * FOR-11-04 (R13.4): date on which the works of a category start. Pure, no Spring.
 *
 * <p>{@code startHalfDays} is the 1-based half-day index of the earliest bar; its day is
 * {@code (h + 1) / 2}, consistent with {@code ScheduleCalculator.finishDate(anchor, day) = anchor + (day - 1)}.
 */
public final class WorksStartResolver {

    private WorksStartResolver() {
    }

    /** @return the start date, or {@code null} when the anchor or the bar is absent (or the index is invalid) */
    public static LocalDate startDate(LocalDate anchor, Integer startHalfDays) {
        if (anchor == null || startHalfDays == null || startHalfDays < 1) {
            return null;
        }
        return anchor.plusDays((startHalfDays + 1) / 2 - 1);
    }
}
