package com.foremen.service.estimate.recalc;

/**
 * FOR-05-05 additional task 6 — published after a committed change of a project's estimate numbers
 * (consumed {@code AFTER_COMMIT}, e.g. by the FOR-05-07 offer reaction and the FOR-05-13
 * collaboration broadcast).
 *
 * @param projectId    the owning project
 * @param estimateId   the changed estimate
 * @param actingUserId the user whose action changed the estimate ({@code null} if unknown)
 * @param trigger      what changed the estimate
 */
public record EstimateChangedEvent(Long projectId, Long estimateId, Long actingUserId, Trigger trigger) {

    /** The action that changed the estimate. */
    public enum Trigger {
        /** A room save (dimensions changed, room added or deleted) re-derived the estimate. */
        ROOMS_CHANGED,
        /** An Estimate-tab batched Save changed the estimate totals. */
        ESTIMATE_SAVED
    }
}
