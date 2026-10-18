package com.foremen.service.ocr;

/**
 * FOR-06-03 (Requirement 3.4, design §5.2) — thrown by {@link OcrService#process} when the OCR
 * provider <strong>is configured</strong> but processing a specific document failed: a provider
 * (Vision / LLM) error, unparseable model output, or no lines extracted.
 *
 * <p>The orchestrating {@code ProcurementDocumentService} (task 5.1) catches this to transition the
 * document to {@code FAILED} while retaining the raw upload and the document row intact (never
 * deleting the stored object or the document), leaving it re-processable (R3.4, R8.3). It is
 * distinct from {@link OcrUnavailableException}, which signals the provider itself is not configured.
 */
public class OcrFailedException extends RuntimeException {

    public OcrFailedException(String message) {
        super(message);
    }

    public OcrFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
