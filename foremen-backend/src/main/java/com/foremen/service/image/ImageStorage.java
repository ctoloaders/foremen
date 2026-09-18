package com.foremen.service.image;

import org.springframework.web.multipart.MultipartFile;

/**
 * Shared, reusable seam for entity images (FOR-04-17, Requirement 7.1), consumed by
 * {@code ConstructionMaterial} images, {@code MaterialProducer} images, and available for later
 * reuse by FOR-04-18 finishing-material photos.
 *
 * <p>The upload is <strong>backend-mediated</strong> (model B): the frontend posts the file to a
 * Foremen backend endpoint, the backend stores the object in a Google Cloud Storage bucket and
 * returns a reference; there is no direct-from-frontend signed-URL upload (Requirement 7.2). The
 * storage model is <strong>object-key based</strong> (model C): the referencing entity persists
 * only the nullable bucket-relative OBJECT KEY, and the public CDN URL is constructed at read time
 * from the object key plus a configured CDN base (Requirement 7.3, 7.4). Object keys are namespaced
 * per entity kind, e.g. {@code producers/{uuid}.{ext}} and
 * {@code construction-materials/{uuid}.{ext}} (Requirement 7.5).
 *
 * <p>All configuration (bucket name, CDN base URL, credentials, maximum upload size and the
 * reconciliation schedule) is read from {@code application.yml}/environment via
 * {@link com.foremen.config.image.ImageStorageProperties}; nothing is hardcoded (Requirement 7.11).
 * When image storage is not configured, a disabled implementation is wired whose {@link #store}
 * rejects uploads gracefully and whose {@link #toCdnUrl} returns {@code null}, so existing entities
 * keep working without images and {@link #isConfigured()} reports {@code false} (Requirement 7.12).
 *
 * <p>This spec defines only the seam. The concrete GCS implementation, the disabled fallback, the
 * upload controller and the reconciliation job are delivered by the sibling tasks 3.2–3.5.
 */
public interface ImageStorage {

    /**
     * Validate and store an uploaded image under the given key namespace, returning the
     * bucket-relative OBJECT KEY (not the CDN URL).
     *
     * <p>Implementations MUST reject any content type outside {@code image/png}, {@code image/jpeg}
     * and {@code image/webp} (Requirement 7.6) and any file larger than the configured maximum
     * upload size (Requirement 7.7) with a client-error, storing nothing. The returned key is
     * namespaced under {@code keyNamespace} (e.g. {@code "producers/"},
     * {@code "construction-materials/"}), producing keys such as {@code producers/{uuid}.{ext}}
     * (Requirement 7.5).
     *
     * @param file the uploaded image; must be a permitted image type within the size limit
     * @param keyNamespace the per-entity-kind prefix under which the object key is created
     * @return the stored bucket-relative object key
     */
    String store(MultipartFile file, String keyNamespace);

    /**
     * Build the public CDN URL from a stored object key as {@code cdnBase + "/" + objectKey}.
     *
     * <p>Pure, null-safe read-time resolution with no I/O: a {@code null} object key yields a
     * {@code null} URL (Requirement 7.3, 7.4).
     *
     * @param objectKey the stored bucket-relative object key, or {@code null}
     * @return the resolved CDN URL, or {@code null} when {@code objectKey} is {@code null}
     */
    String toCdnUrl(String objectKey);

    /**
     * Delete a bucket object if no database row references it. Called by owning services on image
     * replace/detach/delete (Requirement 7.8, 7.9). Implementations MUST re-check the database for
     * any remaining reference before deleting, so a key still used elsewhere is kept.
     *
     * @param objectKey the previously referenced object key; a {@code null} or blank key is a no-op
     */
    void deleteIfOrphan(String objectKey);

    /**
     * Whether image storage is configured (bucket, CDN base and credentials present). When
     * {@code false}, uploads are rejected gracefully and CDN URL resolution returns {@code null}
     * (Requirement 7.12).
     *
     * @return {@code true} when image storage is configured and uploads are accepted
     */
    boolean isConfigured();
}
