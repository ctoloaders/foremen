package com.foremen.service.procurement.planner;

import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkStartResolver.ProjectCategory;
import com.foremen.service.schedule.ScheduleRowDerivation;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scheduled work categories of a project (FOR-11-01 R5.2, R5.8, R5.9): the categories of the current
 * schedule rows (derived from the estimate with {@link ScheduleRowDerivation}) that also have a stored
 * bar. Orphan bars (category no longer in the estimate) are ignored. The start date is the earliest bar
 * of the category, resolved by {@link WorkStartResolver}.
 */
@Service
@RequiredArgsConstructor
public class ScheduledCategoryService {

    private final EstimateLineDao estimateLineDao;
    private final ProjectScheduleDao scheduleDao;
    private final WorkStartResolver workStartResolver;

    /** Scheduled categories by category id, in schedule row order; empty when nothing is scheduled. */
    @Transactional(readOnly = true)
    public Map<Long, Scheduled> scheduled(Long projectId) {
        if (projectId == null) {
            return Map.of();
        }
        List<ScheduleRowDerivation.RowSource> rows = ScheduleRowDerivation.deriveRows(
                estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(projectId));
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<Long> barCategoryIds = new HashSet<>();
        scheduleDao.findByProjectId(projectId).ifPresent(schedule -> {
            for (ProjectScheduleBarEntity bar : schedule.getBars()) {
                barCategoryIds.add(bar.getWorkCategory().getId());
            }
        });
        if (barCategoryIds.isEmpty()) {
            return Map.of();
        }
        Set<ProjectCategory> pairs = new HashSet<>();
        for (ScheduleRowDerivation.RowSource row : rows) {
            Long categoryId = row.category().getId();
            if (barCategoryIds.contains(categoryId)) {
                pairs.add(new ProjectCategory(projectId, categoryId));
            }
        }
        Map<ProjectCategory, LocalDate> starts = workStartResolver.resolve(pairs);
        boolean ru = isRussian();
        Map<Long, Scheduled> result = new LinkedHashMap<>();
        for (ScheduleRowDerivation.RowSource row : rows) {
            WorkCategoryEntity category = row.category();
            if (!barCategoryIds.contains(category.getId())) {
                continue;
            }
            result.put(category.getId(), new Scheduled(category.getId(),
                    ru ? category.getNameRU() : category.getNamePL(),
                    starts.get(new ProjectCategory(projectId, category.getId()))));
        }
        return result;
    }

    /** Batched per request for the projects on a page. */
    @Transactional(readOnly = true)
    public Map<Long, Map<Long, Scheduled>> scheduled(Iterable<Long> projectIds) {
        Map<Long, Map<Long, Scheduled>> result = new LinkedHashMap<>();
        for (Long projectId : projectIds) {
            result.computeIfAbsent(projectId, this::scheduled);
        }
        return result;
    }

    private static boolean isRussian() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
