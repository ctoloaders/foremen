package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 5.2, 6.1): status of a sync run ({@code bank_sync_runs.status},
 * {@code varchar(16)}). {@link #QUEUED} / {@link #RUNNING} are the "active" states — at most one
 * active run per connection (partial UNIQUE index). Also used for the per-account status of
 * {@code bank_sync_run_accounts}.
 */
public enum SyncRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED;

    /** {@code true} for {@link #QUEUED} and {@link #RUNNING}. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
