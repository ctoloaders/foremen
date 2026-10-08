package com.foremen.service.document;

/**
 * FOR-05-08 (Requirements 1.1, 13.1): the create payload for
 * {@code POST /api/signable-documents}. The new document is persisted in {@code DRAFT} with the
 * given project + type binding; {@code templateLocale}, {@code title}, and {@code sourceRef} are
 * optional.
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> carries NO cost/estimate/margin field; {@link #sourceRef}
 * is an opaque reference to the originating object (e.g. an offer id), not an embedded DTO.
 *
 * @param projectId      the owning project id (required)
 * @param documentTypeId the bound document type id (required)
 * @param templateLocale the locale to generate from, or {@code null} for the default
 * @param title          an optional document title, or {@code null}
 * @param sourceRef      an optional opaque originating-object reference, or {@code null}
 */
public record CreateDocumentRequest(
        Long projectId,
        Long documentTypeId,
        String templateLocale,
        String title,
        String sourceRef) {
}
