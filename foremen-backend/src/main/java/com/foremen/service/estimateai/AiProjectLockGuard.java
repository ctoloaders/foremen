package com.foremen.service.estimateai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.exception.ForemenApiException;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-04 (Requirements 4.1, 4.2, 4.3; design B4) — the single project lock of the AI estimate job.
 *
 * <p>While a project has an active (non-terminal) {@code ESTIMATE} job, every write to its rooms and
 * estimate is refused with {@code 409 error.project.ai.locked} and params {@code {jobId, stage}}. The lock
 * is derived from the job row only, so it ends with {@code COMPLETED}/{@code FAILED}, cancel/delete or the
 * sweep. A {@code ROOMS_ONLY} job locks nothing. Reads are never guarded.
 *
 * <p>The job's own room save bypasses the lock through an explicit {@link AiJobContext}, see
 * {@link #bypass(AiJobContext)}.
 */
@Component
@RequiredArgsConstructor
public class AiProjectLockGuard {

    /** Message code of the 409. */
    public static final String LOCKED_MESSAGE = "error.project.ai.locked";

    private static final ThreadLocal<Long> BYPASS_PROJECT = new ThreadLocal<>();

    private final RoomsImportJobDao jobDao;

    /**
     * Refuses the write when the project has an active {@code ESTIMATE} job.
     *
     * @param projectId the project being written; {@code null} is ignored
     * @throws ForemenApiException 409 {@code error.project.ai.locked} with params {@code jobId}, {@code stage}
     */
    public void assertUnlocked(Long projectId) {
        if (projectId == null || projectId.equals(BYPASS_PROJECT.get())) {
            return;
        }
        jobDao.findActiveEstimateJob(projectId).ifPresent(job -> {
            throw locked(job);
        });
    }

    /**
     * Context-aware form (design R3): passes when {@code context} is the project's active {@code ESTIMATE}
     * job (the job's own writes), refuses with 409 when another job is active, and behaves as
     * {@link #assertUnlocked(Long)} for a {@code null} context. An explicit parameter, never a thread-local.
     */
    public void assertUnlocked(Long projectId, AiJobContext context) {
        if (context == null) {
            assertUnlocked(projectId);
            return;
        }
        if (projectId == null) {
            return;
        }
        jobDao.findActiveEstimateJob(projectId).ifPresent(job -> {
            if (!job.getId().equals(context.jobId()) || !projectId.equals(context.projectId())) {
                throw locked(job);
            }
        });
    }

    /** Null-safe static form for services that receive the guard through optional setter injection. */
    public static void assertUnlocked(AiProjectLockGuard guard, Long projectId) {
        if (guard != null) {
            guard.assertUnlocked(projectId);
        }
    }

    /**
     * Opens a scope in which the given job may write its own project's rooms. The job must be the project's
     * active {@code ESTIMATE} job, otherwise the normal lock (or none) applies and a mismatch is a 409.
     * Close the returned scope in a {@code try-with-resources}.
     */
    public Scope bypass(AiJobContext context) {
        if (context == null) {
            return () -> { };
        }
        Optional<RoomsImportJobEntity> active = jobDao.findActiveEstimateJob(context.projectId());
        if (active.isPresent() && !active.get().getId().equals(context.jobId())) {
            throw locked(active.get());
        }
        Long previous = BYPASS_PROJECT.get();
        BYPASS_PROJECT.set(context.projectId());
        return () -> {
            if (previous == null) {
                BYPASS_PROJECT.remove();
            } else {
                BYPASS_PROJECT.set(previous);
            }
        };
    }

    /** A bypass scope; {@link #close()} restores the previous state. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static ForemenApiException locked(RoomsImportJobEntity job) {
        String stage = job.getStatus() == null ? null : job.getStatus().name();
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("jobId", job.getId());
        named.put("stage", stage);
        return new ForemenApiException(HttpStatus.CONFLICT, LOCKED_MESSAGE).withParams(named);
    }
}
