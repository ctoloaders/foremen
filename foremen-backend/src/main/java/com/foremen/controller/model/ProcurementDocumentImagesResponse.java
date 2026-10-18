package com.foremen.controller.model;

/**
 * FOR-06-03 (Requirements 6.4, 8.1; design §5.5/§5.6) — the response of the image-resolution
 * endpoint ({@code GET /api/procurement-documents/{id}/images}). Carries the document id and the
 * resolved <b>raw</b> upload URL plus the <b>processed</b> white-background rendition URL (both
 * resolved by {@code ProcurementDocumentService} from the stored object keys via the shared
 * {@code ImageStorage}, as a CDN URL or a signed download URL).
 *
 * <p>On a {@code FAILED} document the raw URL (and the processed URL, when a rendition was produced)
 * stay available for the viewer; {@code processedImageUrl} is {@code null} until a processed
 * rendition exists.
 *
 * @param id                the document id
 * @param rawImageUrl       resolved URL of the raw upload (nullable)
 * @param processedImageUrl resolved URL of the processed white-background rendition (nullable)
 */
public record ProcurementDocumentImagesResponse(
        Long id,
        String rawImageUrl,
        String processedImageUrl) {
}
