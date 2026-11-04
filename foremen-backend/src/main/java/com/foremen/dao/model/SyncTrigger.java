package com.foremen.dao.model;

/**
 * FOR-07-04 (Requirements 5.2, 6.1): what started a sync run ({@code bank_sync_runs."trigger"},
 * {@code varchar(16)}).
 */
public enum SyncTrigger {
    CALLBACK,
    SCHEDULE,
    MANUAL,
    CONNECT
}
