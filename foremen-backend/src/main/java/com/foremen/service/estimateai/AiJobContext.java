package com.foremen.service.estimateai;
/**
 * FOR-05-04 (Requirement 4.1, 24.2; design B4, R3) — explicit marker that a write is performed by the
 * {@code ESTIMATE} job itself ({@code jobId}) for {@code projectId}, so {@link AiProjectLockGuard} lets it
 * through. The guard verifies that the job really is the project's active {@code ESTIMATE} job.
 *
 * @param packageCode the job's offer package code scoping the materials seeded by an assignment, nullable
 */
public record AiJobContext(Long jobId, Long projectId, String packageCode) {
    public AiJobContext(Long jobId, Long projectId) {
        this(jobId, projectId, null);
    }
}
