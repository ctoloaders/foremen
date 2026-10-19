package com.foremen.service.procurement;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.model.ProcessingStatus;
import com.foremen.service.ProcurementDocumentService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-03 Amendment A1 (R14.3, R15, R17) — the asynchronous OCR worker. It consumes the
 * {@link ProcurementOcrRequested} event published by
 * {@link ProcurementDocumentService#upload}/{@code reprocess} and runs the OCR pipeline on the
 * dedicated {@code ocrTaskExecutor} thread, strictly AFTER the enqueuing transaction commits — so
 * the pipeline never runs on the request thread or inside the upload transaction (the FOR-03-02
 * invitation-dispatch pattern).
 *
 * <p>The worker owns the staged-progress contract: it drives
 * {@link ProcurementDocumentService#processQueued}, passing a stage sink that commits each stage via
 * {@link ProcurementDocumentService#markStage} in its own transaction so a poller observes the
 * progression (A1 R15.2). On reaching a terminal state it emits the single terminal notification to
 * the uploader via {@link ProcurementOcrNotificationEmitter} (A1 R17).
 *
 * <p>Everything here is best-effort and defensive: a failure in the worker logs and stops — the
 * document is left in a terminal {@code FAILED} (set by {@code processQueued}) or, in the unlikely
 * event the worker itself throws before a terminal commit, the crash-recovery sweep re-enqueues it
 * (A1 R18.4). The worker never propagates to anything user-facing (the request already returned).
 */
@Slf4j
@Component
public class ProcurementOcrWorker {

    private final ProcurementDocumentService service;
    private final ProcurementOcrNotificationEmitter notificationEmitter;

    public ProcurementOcrWorker(ProcurementDocumentService service,
                                ProcurementOcrNotificationEmitter notificationEmitter) {
        this.service = service;
        this.notificationEmitter = notificationEmitter;
    }

    /**
     * Runs the OCR pipeline for a queued document after the enqueuing transaction commits.
     *
     * @param event the OCR-requested event carrying the queued document id
     */
    @Async("ocrTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOcrRequested(ProcurementOcrRequested event) {
        if (event == null || event.documentId() == null) {
            return;
        }
        Long documentId = event.documentId();
        ProcessingStatus terminal;
        try {
            terminal = service.processQueued(documentId,
                    stage -> commitStage(documentId, stage));
        } catch (RuntimeException e) {
            // Defensive: processQueued already maps known failures to a terminal FAILED commit; this
            // guards against an unexpected throw. The sweep (A1 R18.4) will re-enqueue a doc left in a
            // non-terminal stage. Log and stop — the request already returned.
            log.error("OCR worker failed unexpectedly for procurement document {}", documentId, e);
            return;
        }

        if (terminal == null || !terminal.isTerminal()) {
            // null = document gone; non-terminal = disabled-provider resting (UPLOADED) — no notify.
            return;
        }

        ProcurementDocumentService.NotificationContext ctx = service.notificationContext(documentId);
        if (ctx != null) {
            notificationEmitter.emitTerminal(terminal, ctx.uploaderId(), ctx.projectId(),
                    documentId, ctx.failureReason());
        }
    }

    /** Commits a single stage transition (best-effort — a stage-commit failure must not abort OCR). */
    private void commitStage(Long documentId, ProcessingStatus stage) {
        try {
            service.markStage(documentId, stage);
        } catch (RuntimeException e) {
            log.warn("Failed to persist OCR stage {} for procurement document {} (continuing): {}",
                    stage, documentId, e.getMessage());
        }
    }
}
