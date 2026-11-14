package com.foremen.dao.model;

/** FOR-11-00 (R11.8): purchase request status. Derived from its positions, never persisted. */
public enum RequestStatus {
    OPEN,
    RELEASED,
    COMPLETED,
    CANCELLED
}
