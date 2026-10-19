package com.foremen.service.procurement;

/**
 * FOR-06-03 Amendment A1 (R14.3) — the application event published when a procurement document has
 * been committed in {@code QUEUED} state (by an upload or a re-process request) and is ready for the
 * asynchronous OCR pipeline.
 *
 * <p>Published inside the (now fast, transactional) upload/reprocess method and consumed by
 * {@code ProcurementOcrWorker} via a {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Async("ocrTaskExecutor")} listener, so the OCR pipeline runs on a worker thread strictly
 * AFTER the enqueuing transaction commits — never on the request thread and never inside that
 * transaction (mirrors the FOR-03-02 invitation-email dispatch pattern). The event carries only the
 * document id; the worker re-loads the entity on its own thread/transaction.
 *
 * @param documentId the id of the {@code QUEUED} procurement document to process
 */
public record ProcurementOcrRequested(Long documentId) {
}
