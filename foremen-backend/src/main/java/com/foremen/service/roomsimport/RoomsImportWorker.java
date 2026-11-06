package com.foremen.service.roomsimport;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.service.roomsimport.model.ImportResult;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (design "Async job + polling") — consumes {@link RoomsImportRequested} on the dedicated
 * {@code roomsImportTaskExecutor}, strictly after the start transaction commits, and drives
 * {@link RoomsImportPipeline} to a terminal state through {@link RoomsImportJobTerminator}.
 *
 * <p>A {@link JobCancelledException} (row removed, or already terminal) is swallowed: no result, no
 * terminal write, no notification. Every other failure becomes a {@code FAILED} job with a stable
 * reason code; a worker that dies before a terminal write is covered by the sweep.
 */
@Slf4j
@Component
public class RoomsImportWorker {

    private final RoomsImportPipeline pipeline;
    private final RoomsImportJobTerminator terminator;

    public RoomsImportWorker(RoomsImportPipeline pipeline, RoomsImportJobTerminator terminator) {
        this.pipeline = pipeline;
        this.terminator = terminator;
    }

    @Async("roomsImportTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(RoomsImportRequested event) {
        if (event == null || event.jobId() == null) {
            return;
        }
        process(event.jobId());
    }

    /** Runs one job to its terminal state; synchronous so tests can call it directly. */
    void process(Long jobId) {
        try {
            ImportResult result = pipeline.run(jobId);
            int updated = terminator.complete(jobId, result);
            if (updated == 0) {
                log.debug("Rooms import job {} finished but was no longer active; result dropped", jobId);
            }
        } catch (JobCancelledException e) {
            log.debug("Rooms import job {} cancelled; worker stops", jobId);
        } catch (RoomsImportFailureException e) {
            log.warn("Rooms import job {} failed: {}", jobId, e.getReason());
            failQuietly(jobId, e.getReason());
        } catch (RuntimeException e) {
            log.error("Rooms import job {} failed unexpectedly", jobId, e);
            failQuietly(jobId, RoomsImportErrors.FAILED_INTERNAL);
        }
    }

    private void failQuietly(Long jobId, String reason) {
        try {
            terminator.fail(jobId, reason);
        } catch (RuntimeException e) {
            // the sweep fails the job once it is stuck
            log.error("Could not mark rooms import job {} failed", jobId, e);
        }
    }
}
