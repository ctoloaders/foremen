package com.foremen.service.document;

/**
 * FOR-05-08 (Requirements 3.5, 3.7, 13.1): the payload for the template-editor "save body" path
 * ({@code PUT /api/signable-documents/{id}/body}) — the edited DRAFT body text to persist.
 *
 * <p>Accepting it is permitted only while the document is {@code DRAFT}; once the document is frozen
 * ({@code PENDING_SIGNATURES}) or {@code SIGNED}, saving the body is rejected with
 * {@code 409 error.document.frozen} (R3.7). Each save recomputes the body hash (R3.5).
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> carries only the body text — no cost/estimate/margin field.
 *
 * @param body the DRAFT body text (may be empty to clear; {@code null} is treated as empty)
 */
public record DocumentBodyInput(String body) {
}
