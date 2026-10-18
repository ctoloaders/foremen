package com.foremen.dao.model;

/**
 * FOR-06-03 (Requirements 1.1, 4.3) — the OCR/parse lifecycle status of a
 * {@link ProcurementDocumentEntity} (design §4.3/§4.4). Stored on
 * {@code procurement_documents.status} (VARCHAR(16), NOT NULL) as the enum name.
 *
 * <ul>
 *   <li>{@code UPLOADED} — the raw file is stored and the document created, OCR not yet run (R2.1);
 *       transient in the configured case, the resting state when the OCR provider is disabled (R3.5).</li>
 *   <li>{@code PROCESSING} — white-background cleanup + OCR + parse + matching in flight (R2.5).</li>
 *   <li>{@code PARSED} — lines extracted and matched; supplier/total/parsedAt set (R3.3).</li>
 *   <li>{@code FAILED} — cleanup/OCR/parse failed or no lines extracted; the raw upload and the
 *       document are retained and the document is re-processable (R3.4, R8).</li>
 * </ul>
 */
public enum ProcessingStatus {
    UPLOADED,
    PROCESSING,
    PARSED,
    FAILED
}
