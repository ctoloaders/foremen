package com.foremen.dao.model;

/**
 * FOR-05-02 — lifecycle of a rooms auto-import job. {@link #COMPLETED} and {@link #FAILED} are
 * terminal; every other status is active (a project may have only one active job).
 */
public enum RoomsImportStatus {
    QUEUED,
    EXTRACTING,
    DETECTING_ROOMS,
    CLASSIFYING,
    MEASURING,
    VALIDATING,
    /** FOR-05-04: ESTIMATE-mode stages after the rooms phase (non-terminal). */
    SAVING_ROOMS,
    PLANNING_WORKS,
    /** FOR-05-04 rework: one assign_work call per resolved assignment, then the summary (non-terminal). */
    ASSIGNING,
    FINALIZING,
    COMPLETED,
    FAILED;

    /** @return {@code true} for {@link #COMPLETED} and {@link #FAILED} */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
