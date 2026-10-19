package com.foremen.dao.model;

/**
 * FOR-06-03 (Requirements 1.1, 4.3; Amendment A1 R15) — the OCR/parse lifecycle status of a
 * {@link ProcurementDocumentEntity} (design §4.3/§4.4 + Amendment A1 §A1.1). Stored on
 * {@code procurement_documents.status} (VARCHAR(16), NOT NULL) as the enum name — the longest value
 * {@code RASTERIZING} is 11 chars, so the column width is unchanged.
 *
 * <p><b>Amendment A1 (asynchronous OCR).</b> Processing is now asynchronous: the upload request
 * commits the row as {@link #QUEUED} and returns immediately, then a background worker advances the
 * document through the fine-grained processing stages, committing each so a poller observes the
 * progression. The former single {@code PROCESSING} state is replaced by the six-stage chain below.
 *
 * <ul>
 *   <li>{@code UPLOADED} — the raw file is stored and the document created, OCR not attempted; the
 *       <b>resting</b> state ONLY when the OCR provider is disabled (R3.5 / A1 R14.4).</li>
 *   <li>{@code QUEUED} — committed by the upload/reprocess request; awaiting the async worker
 *       (A1 R14.1).</li>
 *   <li>{@code RASTERIZING} — the worker is reading the raw bytes + splitting PDF/image pages.</li>
 *   <li>{@code CLEANING} — per-page EXIF orient + corner-detect + white-background cleanup.</li>
 *   <li>{@code OCR_TEXT} — Google Cloud Vision {@code documentTextDetection} per page.</li>
 *   <li>{@code PARSING} — the LLM per-line parse of the concatenated OCR text.</li>
 *   <li>{@code MATCHING} — match-engine over the project candidates + atomic line persistence.</li>
 *   <li>{@code PARSED} — terminal success: lines extracted and matched; supplier/total/parsedAt set
 *       (R3.3).</li>
 *   <li>{@code FAILED} — terminal failure: a stage failed or no lines extracted; the raw upload and
 *       the document are retained and the document is re-processable (R3.4, R8, A1 R18).</li>
 * </ul>
 */
public enum ProcessingStatus {
    UPLOADED,
    QUEUED,
    RASTERIZING,
    CLEANING,
    OCR_TEXT,
    PARSING,
    MATCHING,
    PARSED,
    FAILED;

    /**
     * Whether this status is a non-terminal processing stage (A1 R15.3): the worker is still (or
     * about to be) advancing the document. {@link #UPLOADED} is NOT a processing stage — it is the
     * OCR-disabled resting state. {@link #PARSED}/{@link #FAILED} are terminal.
     *
     * @return {@code true} for {@code QUEUED/RASTERIZING/CLEANING/OCR_TEXT/PARSING/MATCHING}
     */
    public boolean isProcessing() {
        return switch (this) {
            case QUEUED, RASTERIZING, CLEANING, OCR_TEXT, PARSING, MATCHING -> true;
            default -> false;
        };
    }

    /**
     * Whether this status is terminal (A1 R15.3): no further automatic transition will occur.
     *
     * @return {@code true} for {@code PARSED} and {@code FAILED}
     */
    public boolean isTerminal() {
        return this == PARSED || this == FAILED;
    }
}
