package com.foremen.service.roomsimport;

/**
 * FOR-05-02 — event published inside the start transaction once a job is committed {@code QUEUED};
 * consumed by {@link RoomsImportWorker} (mode {@code ROOMS_ONLY}) or by the FOR-05-04
 * {@code EstimateAiWorker} (mode {@code ESTIMATE}) strictly after commit (mirrors
 * {@code ProcurementOcrRequested}).
 *
 * @param jobId the queued job
 * @param mode  the job mode; {@code null} or {@code ROOMS_ONLY} for the FOR-05-02 behaviour
 */
public record RoomsImportRequested(Long jobId, String mode) {

    /** The mode value of the estimate pipeline. */
    public static final String MODE_ESTIMATE = "ESTIMATE";

    /** A rooms-only request (the FOR-05-02 shape). */
    public RoomsImportRequested(Long jobId) {
        this(jobId, null);
    }

    /** {@code true} when the event belongs to the FOR-05-04 estimate worker. */
    public boolean isEstimate() {
        return MODE_ESTIMATE.equals(mode);
    }
}
