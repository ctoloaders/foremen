package com.foremen.controller.model.companyschedule;

/**
 * One overlap {@code Conflict} of a {@link MemberRow} in the cross-project staffing projection
 * (FOR-07-01-cross-project-schedule-core, Requirement 5). A conflict is a pair of that member's
 * datable {@code Worker_Category_Bars} whose inclusive absolute date intervals overlap — the worker
 * is double-booked across projects or categories in the same window.
 *
 * <p>The two values are zero-based indices into the owning {@link MemberRow#bars()} list. Only
 * datable {@code Worker_Category_Bars} ({@link BarKind#WORKER_CATEGORY}) form a conflict:
 * {@code Foreman_Span_Bars} and {@code Undatable_Bars} never participate (Requirement 5.4, 5.5), and
 * a conflict never spans two different users (Requirement 5.6).
 *
 * @param barIndexA the index of the first conflicting bar in the member row's {@code bars} list
 * @param barIndexB the index of the second conflicting bar in the member row's {@code bars} list
 */
public record ConflictPair(
        int barIndexA,
        int barIndexB
) {}
