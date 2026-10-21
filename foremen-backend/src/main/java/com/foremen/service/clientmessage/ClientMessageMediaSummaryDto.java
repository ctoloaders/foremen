package com.foremen.service.clientmessage;

import com.foremen.dao.model.MediaKind;

/**
 * FOR-06-12 (Requirements 1.3, 4.4) — the per-attachment summary read model of one
 * {@code ClientMessageMedia}, served inside {@link ClientMessageDto#media()}. Mirrors the
 * {@code DocumentMediaSummaryDto} shape: metadata only (the bytes live in object storage), with the
 * {@link #kind} enum localized on the frontend (no DB i18n column, R11.1).
 *
 * <p><b>Signed download URL (R4.4).</b> {@link #downloadUrl} is a short-lived V4 signed GET URL for
 * the PRIVATE object, <em>not</em> a public CDN link. It is <strong>populated by the
 * service/mapper</strong> via {@code ImageStorage.signedDownloadUrl(...)}; the mapper itself never
 * calls storage — the service supplies the resolved URL per media id so the mapper stays pure. The
 * field is left {@code null} when no URL was supplied for the attachment.
 *
 * @param id          the media id
 * @param fileName    the original file name
 * @param contentType the MIME content type
 * @param sizeBytes   the file size in bytes
 * @param kind        {@code IMAGE} / {@code VIDEO} / {@code DOCUMENT}
 * @param downloadUrl the per-media short-lived signed download URL (service-supplied), or
 *                    {@code null} when none was resolved
 */
public record ClientMessageMediaSummaryDto(
        Long id,
        String fileName,
        String contentType,
        long sizeBytes,
        MediaKind kind,
        String downloadUrl) {
}
