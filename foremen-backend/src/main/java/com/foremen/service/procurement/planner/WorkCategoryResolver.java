package com.foremen.service.procurement.planner;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure resolver of the work category a purchase position is linked to (FOR-11-01 R5.1-R5.3, R5.8).
 *
 * <p>Order: a manual override wins; otherwise tier 1 (categories of the project's estimate works that
 * consume the material type) intersected with the scheduled categories; if that is empty, tier 2 (the
 * whole catalog) intersected likewise. Within the first non-empty tier the earliest start date wins
 * (nulls last), then name, then id; the rest are returned as candidates. Nothing found gives
 * {@link Source#NONE}.
 */
public final class WorkCategoryResolver {

    public enum Source { MANUAL, AUTO, NONE }

    /** A scheduled category of a project with its (possibly unknown) start date. */
    public record Scheduled(Long categoryId, String name, LocalDate startDate) {
    }

    public record Resolution(Long categoryId, Source source, List<Scheduled> candidates) {
    }

    private static final Comparator<Scheduled> ORDER = Comparator
            .comparing(Scheduled::startDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(Scheduled::name, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(Scheduled::categoryId, Comparator.nullsLast(Comparator.<Long>naturalOrder()));

    public Resolution resolve(Long manualCategoryId,
                              Long materialTypeId,
                              Map<Long, Set<Long>> estimateCategoriesByType,
                              Map<Long, Set<Long>> catalogCategoriesByType,
                              Map<Long, Scheduled> scheduledByCategory) {
        if (manualCategoryId != null) {
            return new Resolution(manualCategoryId, Source.MANUAL, List.of());
        }
        if (materialTypeId == null || scheduledByCategory == null || scheduledByCategory.isEmpty()) {
            return none();
        }
        List<Scheduled> matches = intersect(estimateCategoriesByType, materialTypeId, scheduledByCategory);
        if (matches.isEmpty()) {
            matches = intersect(catalogCategoriesByType, materialTypeId, scheduledByCategory);
        }
        if (matches.isEmpty()) {
            return none();
        }
        matches.sort(ORDER);
        return new Resolution(matches.get(0).categoryId(), Source.AUTO,
                List.copyOf(matches.subList(1, matches.size())));
    }

    private static List<Scheduled> intersect(Map<Long, Set<Long>> categoriesByType, Long typeId,
                                             Map<Long, Scheduled> scheduledByCategory) {
        List<Scheduled> result = new ArrayList<>();
        Set<Long> categories = categoriesByType == null ? null : categoriesByType.get(typeId);
        if (categories == null) {
            return result;
        }
        for (Long categoryId : categories) {
            Scheduled scheduled = scheduledByCategory.get(categoryId);
            if (scheduled != null) {
                result.add(scheduled);
            }
        }
        return result;
    }

    private static Resolution none() {
        return new Resolution(null, Source.NONE, List.of());
    }
}
