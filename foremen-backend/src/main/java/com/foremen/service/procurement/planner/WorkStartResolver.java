package com.foremen.service.procurement.planner;

import com.foremen.dao.ProjectScheduleBarDao;
import com.foremen.service.schedule.ScheduleCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the calendar start date of a scheduled work category (FOR-11-01 R5.5): the earliest bar
 * of the category in the project's schedule, converted with the same rule the Gantt uses for dates.
 *
 * <p>Rule (copied from {@code ScheduleCalculator.finishDateFromHalfDays}, applied to the start): every
 * calendar day is a working day, day 1 is the project start date, and half-days {@code 2k-1} and
 * {@code 2k} both fall on calendar day {@code k}. Date = {@code project.startDate +
 * calendarDayOfHalfDay(minStartHalfDays) - 1}. A pair without schedule, bar or project start date has
 * no entry in the result.
 */
@Component
@RequiredArgsConstructor
public class WorkStartResolver {

    /** A (project, work category) pair. */
    public record ProjectCategory(Long projectId, Long workCategoryId) {
    }

    private final ProjectScheduleBarDao barDao;

    /** One query for all pairs; returns only the pairs that have a start date. */
    @Transactional(readOnly = true)
    public Map<ProjectCategory, LocalDate> resolve(Collection<ProjectCategory> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return Map.of();
        }
        Set<ProjectCategory> wanted = new HashSet<>(pairs);
        Set<Long> projectIds = new HashSet<>();
        Set<Long> categoryIds = new HashSet<>();
        for (ProjectCategory pair : wanted) {
            projectIds.add(pair.projectId());
            categoryIds.add(pair.workCategoryId());
        }
        Map<ProjectCategory, LocalDate> result = new HashMap<>();
        for (ProjectScheduleBarDao.EarliestBarRow row
                : barDao.findEarliestBarStarts(projectIds, categoryIds)) {
            ProjectCategory key = new ProjectCategory(row.projectId(), row.categoryId());
            if (!wanted.contains(key)) {
                continue;
            }
            LocalDate date = toDate(row.projectStartDate(), row.minStartHalfDays());
            if (date != null) {
                result.put(key, date);
            }
        }
        return result;
    }

    /** Start date of a bar beginning at {@code startHalfDays}; null when either input is null. */
    static LocalDate toDate(LocalDate anchor, Integer startHalfDays) {
        if (anchor == null || startHalfDays == null) {
            return null;
        }
        return anchor.plusDays(ScheduleCalculator.calendarDayOfHalfDay(startHalfDays) - 1L);
    }
}
