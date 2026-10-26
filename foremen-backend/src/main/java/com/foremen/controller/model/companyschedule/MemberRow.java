package com.foremen.controller.model.companyschedule;

import java.util.List;

/**
 * One {@code Member_Row} of the cross-project staffing projection
 * (FOR-07-01-cross-project-schedule-core, Requirement 1) — one logical row per {@code User} who
 * contributes at least one included bar in an {@code Accessible_Project}. A single row aggregates
 * that user's bars across every accessible project (Requirement 1.5): a WORKER's row carries their
 * {@code Worker_Category_Bars}, a FOREMAN's row carries one {@code Foreman_Span_Bar} per led project.
 *
 * <p>{@link #companyRole()} is the {@code projectRole.code} of the member contributing the bars
 * (e.g. {@code WORKER} / {@code FOREMAN}), letting the consumer distinguish and group foremen apart
 * from workers (Requirement 1.4). A row is emitted only when it has at least one included bar after
 * all filters (Requirement 10.3), and the service never emits a row with no identifiable user
 * (Requirement 2.3).
 *
 * <p><strong>No cost-bearing field (Requirement 8.1).</strong> The row carries only the user's
 * identity, their company role, their time/assignment bars, and the per-member conflict set — no
 * money, man-days, rate, or pay-tier value.
 *
 * @param userId      the contributing user's id
 * @param userName    the contributing user's name
 * @param companyRole the {@code projectRole.code} of the contributing member (e.g. {@code WORKER},
 *                    {@code FOREMAN})
 * @param bars        the user's {@link ProjectedBar}s across all accessible projects, in the fixed
 *                    deterministic order of Requirement 10.4
 * @param conflicts   the per-member overlap conflicts, each referencing two indices into
 *                    {@link #bars()} (Requirement 5); empty when the member has no overlap
 */
public record MemberRow(
        long userId,
        String userName,
        String companyRole,
        List<ProjectedBar> bars,
        List<ConflictPair> conflicts
) {}
