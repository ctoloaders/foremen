package com.foremen.service.procurement;

/**
 * FOR-07-04 (Requirement 28.2; design §Reverse document matching) — published by
 * {@code ProcurementDocumentService} inside the OCR worker transaction when a procurement document
 * reaches {@code PARSED} (first parse and every successful re-process). The bank-ledger reverse
 * document matcher listens {@code AFTER_COMMIT} and runs only for {@code UNMATCHED} documents.
 *
 * @param documentId the parsed procurement document id
 */
public record ProcurementDocumentParsedEvent(Long documentId) {
}
