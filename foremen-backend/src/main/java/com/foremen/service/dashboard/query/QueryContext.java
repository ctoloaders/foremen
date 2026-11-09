package com.foremen.service.dashboard.query;

import java.time.LocalDate;
import java.util.Set;

/**
 * Caller and filter context of one widget query.
 *
 * @param allowedProjectIds projects the caller may see (from {@code ProjectAccessCache}); ignored for admins
 * @param admin             exact ADMIN role: project scope is skipped
 * @param dateFrom          inclusive lower bound of the global date filter, may be null
 * @param dateTo            inclusive upper bound, may be null
 * @param boundProjectId    project of a Project_Widget placement, may be null
 * @param locale            {@code ru} or {@code pl}
 */
public record QueryContext(Long userId, String roleCode, Set<Long> allowedProjectIds, boolean admin,
                           LocalDate dateFrom, LocalDate dateTo, Long boundProjectId, String locale) {

    public QueryContext {
        allowedProjectIds = allowedProjectIds == null ? Set.of() : Set.copyOf(allowedProjectIds);
        locale = locale == null ? "ru" : locale;
    }

    public QueryContext withDates(LocalDate from, LocalDate to) {
        return new QueryContext(userId, roleCode, allowedProjectIds, admin, from, to, boundProjectId, locale);
    }
}
