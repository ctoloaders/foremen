package com.foremen.controller.model.readiness;

import java.util.List;

import com.foremen.service.readiness.AreaContribution;
import com.foremen.service.readiness.ProjectReadinessResult;
import com.foremen.service.readiness.ReadinessState;

/**
 * The REST response payload returned by {@code GET /api/projects/{projectId}/readiness} (FOR-05-11
 * Requirement 4, Decision D2/D4/D5). It is the controller-facing projection of the service-level
 * {@link ProjectReadinessResult}: the project id, the equal-weight {@link #overallPercentage()}
 * (Requirement 4.3), the rolled-up {@link #overallState()} (Requirement 4.4), and the ordered list
 * of per-area contributions the caller may READ, in the stable declared area order (Requirement 4.5,
 * 7.1).
 *
 * <p>Each entry is an {@link AreaView} carrying the area's stable {@code key}, its localized name
 * key, its {@code percentage}, its {@code state}, and its ordered list of {@link OutstandingItemView}
 * outstanding items (Requirement 4.6). The view carries nothing an area's own ABAC rule would hide:
 * an item's {@code params} are non-sensitive counts/identifiers only (Requirement 4.8).
 *
 * @param projectId         the project the readiness was computed for
 * @param overallPercentage the equal-weight mean of the included area percentages, {@code 0..100}
 *                          ({@code 0} when no area is included)
 * @param overallState      the {@code BLOCKED > PARTIAL > DONE} roll-up of the included area states
 *                          ({@code BLOCKED} when no area is included)
 * @param areas             the included area contributions in declared order (Requirement 7.1)
 */
public record ProjectReadinessView(
        Long projectId,
        int overallPercentage,
        ReadinessState overallState,
        List<AreaView> areas
) {

    /**
     * Maps a service-level {@link ProjectReadinessResult} to its REST response view.
     *
     * @param result the aggregated readiness result (never {@code null})
     * @return the controller-facing view
     */
    public static ProjectReadinessView from(ProjectReadinessResult result) {
        return new ProjectReadinessView(
                result.projectId(),
                result.overallPercentage(),
                result.overallState(),
                result.areas().stream().map(AreaView::from).toList());
    }

    /**
     * One area's contribution as returned to the client (Requirement 4.6, 7.2).
     *
     * @param key        the stable, lowercase area key (a valid React list key)
     * @param nameKey    the i18n key for the area's display name (reuses {@code workspace.tab.*})
     * @param percentage the area's readiness percentage, {@code 0..100}
     * @param state      the area's readiness state
     * @param items      the ordered list of outstanding items (empty iff fully ready)
     */
    public record AreaView(
            String key,
            String nameKey,
            int percentage,
            ReadinessState state,
            List<OutstandingItemView> items
    ) {

        /** Maps a service-level {@link AreaContribution} to its REST response view. */
        public static AreaView from(AreaContribution contribution) {
            return new AreaView(
                    contribution.key(),
                    contribution.nameKey(),
                    contribution.percentage(),
                    contribution.state(),
                    contribution.items().stream().map(OutstandingItemView::from).toList());
        }
    }
}
