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
    COMPLETED,
    FAILED;

    /** @return {@code true} for {@link #COMPLETED} and {@link #FAILED} */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
