package com.foremen.service.readiness;

/**
 * The shared tri-state of an area's (or the overall) project readiness (FOR-05-11 Requirement 1.2,
 * Decision D2/D5).
 *
 * <p>This is the aggregate readiness enum used by every {@link ProjectReadinessContributor} and by
 * the aggregator. It is deliberately a <em>new</em> three-valued enum rather than a reuse of a
 * narrower per-area enum: the team area's own {@code com.foremen.service.team.ReadinessState} has
 * only {@code DONE}/{@code BLOCKED}, and the schedule area's {@code ScheduleReadiness.State} has its
 * own semantics. The team and schedule contributors map their area enum onto this shared enum
 * (team {@code DONE → DONE}, {@code BLOCKED → BLOCKED}) so the aggregate stays decoupled from any
 * one area's enum.
 */
public enum ReadinessState {

    /** The area is fully ready (contributes {@code percentage = 100}, empty {@code items}). */
    DONE,

    /** The area is partially ready (some sub-conditions still outstanding). */
    PARTIAL,

    /** The area is blocked — its first step is not yet done. */
    BLOCKED
}
