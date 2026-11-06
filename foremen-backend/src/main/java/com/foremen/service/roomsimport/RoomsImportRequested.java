package com.foremen.service.roomsimport;

/**
 * FOR-05-02 — event published inside the start transaction once a job is committed {@code QUEUED};
 * consumed by {@link RoomsImportWorker} strictly after commit (mirrors {@code ProcurementOcrRequested}).
 *
 * @param jobId the queued job
 */
public record RoomsImportRequested(Long jobId) {
}
