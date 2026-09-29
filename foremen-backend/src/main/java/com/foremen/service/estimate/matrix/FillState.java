package com.foremen.service.estimate.matrix;

/**
 * The fill state of an assigned cell's materials (FOR-05-05, R7, R8.1). Exactly one of three states:
 * every material line concrete ({@link #filled}), some but not all ({@link #partial}), or none
 * ({@link #placeholder}). Serialized in lower-case to mirror the frontend {@code FillState} union
 * {@code 'filled' | 'partial' | 'placeholder'}.
 */
public enum FillState {
    /** Every material line has a chosen concrete product (fully filled; the cost range collapses). */
    filled,
    /** Some but not all material lines are concrete. */
    partial,
    /** No material line is concrete (placeholder-only); also the state of a material-less cell. */
    placeholder
}
