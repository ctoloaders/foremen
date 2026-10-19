package com.foremen.service.ocr;

/**
 * FOR-06-03 Amendment A1 (R15.1, R15.2) — a progress callback the OCR orchestrator invokes as it
 * enters each internal processing phase, so the asynchronous worker can advance and persist the
 * document's fine-grained {@code status}/stage ({@code CLEANING}/{@code OCR_TEXT}/{@code PARSING})
 * while {@link OcrService#process} runs.
 *
 * <p>The callback is best-effort and side-effect-only from the OCR layer's view: implementations
 * update + commit the document stage; a listener that throws MUST NOT abort OCR, so
 * {@link OcrService} invokes it defensively. A {@code null} listener (the
 * {@link #NOOP no-op}) means "do not report intermediate stages" — used by the synchronous callers
 * and tests that do not care about progress.
 */
@FunctionalInterface
public interface OcrStageListener {

    /** A listener that ignores every stage callback (the default when progress is not tracked). */
    OcrStageListener NOOP = stage -> { };

    /**
     * Invoked when the OCR pipeline enters a new processing stage.
     *
     * @param stage the stage just entered
     */
    void onStage(OcrStage stage);

    /**
     * The OCR pipeline's internal processing phases reported to an {@link OcrStageListener}. These
     * map to the non-terminal {@code ProcessingStatus} stages the async worker persists:
     * {@link #CLEANING} → {@code CLEANING}, {@link #OCR_TEXT} → {@code OCR_TEXT},
     * {@link #PARSING} → {@code PARSING}. The {@code RASTERIZING} and {@code MATCHING} stages are
     * driven by the worker around the OCR call, so they are not reported through this listener.
     */
    enum OcrStage {
        /** Per-page white-background cleanup + corner-detect is starting. */
        CLEANING,
        /** Google Cloud Vision text detection is starting. */
        OCR_TEXT,
        /** The LLM per-line parse is starting. */
        PARSING
    }
}
