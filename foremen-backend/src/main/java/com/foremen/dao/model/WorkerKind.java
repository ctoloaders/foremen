package com.foremen.dao.model;

/**
 * FOR-05-09 (D8) — the kind of a WORKER record created through the Worker_Record_Flow.
 * Stored on {@code users.worker_kind} (nullable); every non-worker-record user keeps it
 * {@code null}, which a WORKER view treats as {@link #PERSON}.
 */
public enum WorkerKind {
    PERSON,
    COMPANY
}
