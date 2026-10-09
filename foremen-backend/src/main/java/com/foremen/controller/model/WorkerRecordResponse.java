package com.foremen.controller.model;

/**
 * Response payload for a successful {@code Worker_Record_Flow} ({@code POST /api/users/worker},
 * FOR-05-09 Requirement 13 criterion 1). Carries the new WORKER user's id, its stored email (or an
 * empty string when no email was supplied), the project id, and the new membership id. It never
 * carries a password, token, worker rate, tier percentage, or cost.
 */
public record WorkerRecordResponse(
        Long userId,
        String email,
        Long projectId,
        Long membershipId
) {}
