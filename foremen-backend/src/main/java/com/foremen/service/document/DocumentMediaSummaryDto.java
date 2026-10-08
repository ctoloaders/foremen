package com.foremen.service.document;

import com.foremen.dao.model.DocumentMediaKind;

/**
 * FOR-05-08 (Requirements 7.1, 7.2): the summary read model of one {@code DocumentMedia} — the
 * metadata of an evidence/attachment file whose bytes live in object storage (FOR-12). Served inside
 * {@link SignableDocumentDto#media}.
 *
 * <p>{@link #kind} is an enum localized on the frontend (no DB i18n column, R12.1).
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> client-reachable; carries NO cost/estimate/margin field.
 *
 * @param id          the media id
 * @param fileName    the original file name
 * @param contentType the MIME content type
 * @param sizeBytes   the file size in bytes
 * @param storageUri  the object-storage uri (FOR-12)
 * @param kind        {@code SCAN} / {@code TABLET_INITIAL} / {@code RENDERED_BODY} / {@code ATTACHMENT}
 */
public record DocumentMediaSummaryDto(
        Long id,
        String fileName,
        String contentType,
        Long sizeBytes,
        String storageUri,
        DocumentMediaKind kind) {
}
