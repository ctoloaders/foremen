package com.foremen.controller.model;

import com.foremen.dao.model.RoomsImportStatus;

/** FOR-05-02 (Requirement 1.3) — body of {@code 202 Accepted} for a started import. */
public record RoomsImportStartResponse(Long jobId, RoomsImportStatus status) {
}
