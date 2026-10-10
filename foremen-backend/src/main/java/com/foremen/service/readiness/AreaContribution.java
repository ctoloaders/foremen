package com.foremen.service.readiness;

import java.util.List;

/**
 * The uniform contribution a {@link ProjectReadinessContributor} returns for one design-stage area
 * (FOR-05-11 Requirement 1.1/1.2, Decision D2).
 *
 * <p>Well-formedness contract (Requirement 1.2, 1.3): {@code percentage} is an integer in the
 * inclusive range {@code [0, 100]}; {@code state} is exactly one of {@link ReadinessState#DONE},
 * {@link ReadinessState#PARTIAL}, {@link ReadinessState#BLOCKED}; and {@code items} is empty if and
 * only if the area is fully ready ({@code state == DONE} and {@code percentage == 100}).
 *
 * @param key        the stable, lowercase area key (equal to {@link ProjectReadinessContributor#areaKey()})
 * @param nameKey    the i18n key for the area's display name (reuses {@code workspace.tab.*})
 * @param percentage the area's readiness percentage, {@code 0..100}
 * @param state      the area's readiness state
 * @param items      the ordered list of outstanding items (empty iff fully ready)
 */
public record AreaContribution(
        String key,
        String nameKey,
        int percentage,
        ReadinessState state,
        List<OutstandingItem> items
) {}
