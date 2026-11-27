package com.foremen.service.estimate;

/**
 * FOR-05-04 (design B11, Requirement 21.7): who replays a set of staged edits. A {@link #MANUAL} replay
 * (estimate preview and Save) clears the AI marker of every cell it changes; only the AI scratch service
 * replays with {@link #AI}, so the overlay can write the markers afterwards.
 */
public enum ReplayOrigin {
    /** User edits from the estimate matrix: changed cells lose their AI marker. */
    MANUAL,
    /** The AI scratch replay (job or chat): markers are kept and then set by the overlay. */
    AI
}
