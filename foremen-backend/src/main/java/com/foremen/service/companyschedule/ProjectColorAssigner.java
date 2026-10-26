package com.foremen.service.companyschedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Pure, static assignment of a deterministic {@code Project_Color} index to every project appearing
 * in the cross-project staffing projection (FOR-07-01-cross-project-schedule-core, Requirement 3).
 *
 * <p>This is one of the side-effect-free aggregation helpers of the pure projector (design
 * §"Pure projector"): it has no database, no Spring context, and no clock, so its result depends
 * only on the input project-id set and is directly unit- and property-testable (mirrors
 * {@code ScheduleCalculator}). It lives in its own class so the sibling projector helpers (date
 * projection, overlap/conflict detection, the {@code project(...)} orchestration) can be authored
 * independently without any of them overwriting each other.
 *
 * <p><strong>Determinism (Requirement 3.2).</strong> The distinct project ids are ranked in ascending
 * order and each id is assigned the palette index {@code rank % }{@value #PALETTE_SIZE}. The result
 * depends only on the <em>set</em> of project ids, not on their order in the input, how many times
 * each appears, or request timing — so two projections built from the same set of project ids assign
 * each id an identical {@code colorIndex} (Requirement 3.2). The returned map's iteration order is the
 * same ascending-id order, so callers that build the legend from it get a stable legend order too.
 *
 * <p><strong>Shared index (Requirement 3.1).</strong> The projector uses the single index this helper
 * assigns to a project id for <em>every</em> bar of that project — both {@code Worker_Category_Bar}s
 * and {@code Foreman_Span_Bar}s — and for that project's legend entry, so a project reads as one
 * color across the whole response.
 */
public final class ProjectColorAssigner {

    /**
     * The number of distinct palette slots a consumer renders. Ranks beyond this wrap with
     * {@code rank % PALETTE_SIZE} (Requirement 3), so more projects than slots still get a stable,
     * deterministic index; the frontend palette (FOR-07-02) owns the actual colors.
     */
    public static final int PALETTE_SIZE = 12;

    private ProjectColorAssigner() {
    }

    /**
     * Assigns each distinct project id a deterministic {@code Project_Color} index (Requirement 3.1,
     * 3.2). The distinct ids are ranked ascending and mapped to {@code rank % }{@value #PALETTE_SIZE};
     * duplicate ids collapse to a single entry and {@code null} entries are ignored. The returned map
     * is ordered by ascending project id so the derived legend order is stable; it is unmodifiable.
     *
     * @param projectIdsInProjection the project ids appearing in the projection, in any order and with
     *                               possible duplicates or {@code null}s; may be empty or {@code null}
     * @return an unmodifiable, ascending-id-ordered map from each distinct project id to its palette
     *         index in {@code [0, }{@value #PALETTE_SIZE}{@code )}; empty when there are no ids
     */
    public static Map<Long, Integer> assignColors(List<Long> projectIdsInProjection) {
        if (projectIdsInProjection == null || projectIdsInProjection.isEmpty()) {
            return Map.of();
        }
        // De-duplicate and sort ascending so the rank of each id is a pure function of the id set.
        TreeSet<Long> distinctAscending = new TreeSet<>();
        for (Long projectId : projectIdsInProjection) {
            if (projectId != null) {
                distinctAscending.add(projectId);
            }
        }
        List<Long> ranked = new ArrayList<>(distinctAscending);
        Map<Long, Integer> colors = new LinkedHashMap<>(ranked.size() * 2);
        for (int rank = 0; rank < ranked.size(); rank++) {
            colors.put(ranked.get(rank), rank % PALETTE_SIZE);
        }
        return Collections.unmodifiableMap(colors);
    }
}
