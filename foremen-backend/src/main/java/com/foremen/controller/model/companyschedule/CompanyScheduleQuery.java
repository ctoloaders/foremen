package com.foremen.controller.model.companyschedule;

import java.time.LocalDate;
import java.util.List;

/**
 * FOR-07-01 (Requirements 7.2, 7.3, 7.4, 7.5): the normalized, immutable set of server-side filters
 * the {@code Cross_Project_Schedule_Controller} passes to the {@code Cross_Project_Schedule_Service}
 * when building the company staffing projection. It is the <em>complete</em> query surface of the
 * Gantt read endpoint — deliberately no pagination / sort / RSQL {@code query} parameter
 * (Requirement 11.3, 11.4).
 *
 * <p><strong>Null means "no filter" (omitted), not "match nothing".</strong> Each field is
 * {@code null} exactly when the caller supplied no value for that dimension, in which case the
 * service applies no restriction on that dimension:
 * <ul>
 *   <li>{@link #roles()} {@code null} ⇒ both {@code FOREMAN} and {@code WORKER} (Requirement 7.2);</li>
 *   <li>{@link #projectIds()} {@code null} ⇒ all Accessible_Projects (Requirement 7.3);</li>
 *   <li>{@link #memberIds()} {@code null} ⇒ all members (Requirement 7.4);</li>
 *   <li>{@link #from()} / {@link #to()} {@code null} ⇒ that Date_Window bound is open
 *       (Requirement 7.5); both {@code null} ⇒ no Date_Window at all (Requirement 7.6).</li>
 * </ul>
 *
 * <p><strong>Normalization</strong> (compact constructor):
 * <ul>
 *   <li>{@link #roles()}: each value is trimmed and upper-cased; {@code null}/blank entries are
 *       dropped; the result is an immutable copy. An absent or empty-after-cleaning list is
 *       normalized back to {@code null} so it reads as "no Role_Filter".</li>
 *   <li>{@link #projectIds()} / {@link #memberIds()}: {@code null} entries are dropped and the
 *       result is an immutable copy; an absent or empty-after-cleaning list is normalized back to
 *       {@code null} so it reads as "no filter".</li>
 *   <li>{@link #from()} / {@link #to()}: carried through unchanged (the {@code from > to} validation
 *       is a controller concern, Requirement 7.8).</li>
 * </ul>
 *
 * @param roles      the Role_Filter over {@code projectRole.code} ({@code FOREMAN} / {@code WORKER});
 *                   {@code null} ⇒ both roles (Requirement 7.2)
 * @param projectIds the Project_Filter; out-of-scope ids are ignored downstream and never leaked;
 *                   {@code null} ⇒ all Accessible_Projects (Requirement 7.3)
 * @param memberIds  the Member_Filter over {@code User} ids; {@code null} ⇒ all members
 *                   (Requirement 7.4)
 * @param from       the Date_Window inclusive lower bound; {@code null} ⇒ open lower bound
 *                   (Requirement 7.5)
 * @param to         the Date_Window inclusive upper bound; {@code null} ⇒ open upper bound
 *                   (Requirement 7.5)
 */
public record CompanyScheduleQuery(
        List<String> roles,
        List<Long> projectIds,
        List<Long> memberIds,
        LocalDate from,
        LocalDate to
) {

    public CompanyScheduleQuery {
        roles = normalizeRoles(roles);
        projectIds = normalizeIds(projectIds);
        memberIds = normalizeIds(memberIds);
    }

    /**
     * Trim + upper-case each role code, drop {@code null}/blank entries, and return an immutable
     * copy — or {@code null} when nothing remains (so it reads as "no Role_Filter").
     */
    private static List<String> normalizeRoles(List<String> roles) {
        if (roles == null) {
            return null;
        }
        List<String> cleaned = roles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> r.trim().toUpperCase())
                .toList();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Drop {@code null} entries and return an immutable copy — or {@code null} when nothing remains
     * (so it reads as "no filter").
     */
    private static List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) {
            return null;
        }
        List<Long> cleaned = ids.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
