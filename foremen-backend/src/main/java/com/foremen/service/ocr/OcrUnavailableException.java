package com.foremen.service.ocr;

/**
 * FOR-06-03 (Requirement 3.5, design §5.2) — thrown by {@link OcrService#process} when the OCR
 * provider is <strong>not configured</strong> (the {@code DisabledOcrService} fallback is wired
 * because {@code foremen.ocr.enabled} is false or absent).
 *
 * <p>This is the signal that OCR is unavailable rather than that a specific document failed to
 * process: the orchestrating {@code ProcurementDocumentService} (task 5.1) distinguishes it from
 * {@link OcrFailedException} to leave a freshly uploaded document in status {@code UPLOADED} (OCR
 * was never attempted), while marking it {@code FAILED} only on an explicit re-process request
 * against a disabled provider. The application starts and runs normally without the OCR provider
 * configured (R3.5).
 */
public class OcrUnavailableException extends RuntimeException {

    public OcrUnavailableException(String message) {
        super(message);
    }

    public OcrUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
