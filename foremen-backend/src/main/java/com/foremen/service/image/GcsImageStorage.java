package com.foremen.service.image;

import com.foremen.config.image.ImageStorageConfiguredCondition;
import com.foremen.config.image.ImageStorageProperties;
import com.foremen.exception.ForemenValidationException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Google Cloud Storage backed {@link ImageStorage} (FOR-04-17, Requirement 7). Active only when
 * image storage is configured (bucket + CDN base present) via
 * {@link ImageStorageConfiguredCondition}; otherwise the disabled fallback (task 3.3) is wired.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #store} validates the upload's content type against {@code image/png},
 *       {@code image/jpeg} and {@code image/webp} (Requirement 7.6) and its size against the
 *       configured maximum (Requirement 7.7), rejecting a violation with a client error
 *       (HTTP 400 via {@link ForemenValidationException}) and storing nothing; on success it
 *       uploads to the configured bucket under a per-entity-kind namespaced key
 *       {@code {namespace}/{uuid}.{ext}} (Requirement 7.3, 7.5) and returns the object key.</li>
 *   <li>{@link #toCdnUrl} builds {@code cdnBase + "/" + objectKey}, null-safe (Requirement 7.4).</li>
 *   <li>{@link #deleteIfOrphan} re-checks the DB via {@link ImageReferenceLookup} before deleting
 *       the bucket object, keeping any key still referenced (Requirement 7.8, 7.9).</li>
 * </ul>
 *
 * <p>Bucket name, CDN base URL and credentials are read from {@link ImageStorageProperties} — never
 * hardcoded (Requirement 7.11).
 */
@Slf4j
@Component
@Conditional(ImageStorageConfiguredCondition.class)
public class GcsImageStorage implements ImageStorage {

    /** Allowed image content types → the canonical file extension used in the object key. */
    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    /** Fallback maximum upload size when {@code foremen.image-storage.max-upload-size} is unset. */
    private static final DataSize DEFAULT_MAX_UPLOAD_SIZE = DataSize.ofMegabytes(5);

    private final ImageStorageProperties properties;
    private final ImageReferenceLookup referenceLookup;
    private final DataSize maxUploadSize;

    private Storage storage;

    public GcsImageStorage(ImageStorageProperties properties, ImageReferenceLookup referenceLookup) {
        this.properties = properties;
        this.referenceLookup = referenceLookup;
        this.maxUploadSize = parseMaxUploadSize(properties.maxUploadSize());
    }

    @PostConstruct
    void init() {
        this.storage = buildStorage();
    }

    @Override
    public String store(MultipartFile file, String keyNamespace) {
        if (file == null || file.isEmpty()) {
            throw new ForemenValidationException("error.image.empty");
        }

        String contentType = file.getContentType();
        String extension = contentType == null ? null : ALLOWED_CONTENT_TYPES.get(contentType.toLowerCase());
        if (extension == null) {
            // Requirement 7.6: reject a non-image content type, storing nothing.
            throw new ForemenValidationException("error.image.content.type", contentType);
        }

        if (file.getSize() > maxUploadSize.toBytes()) {
            // Requirement 7.7: reject an oversized upload, storing nothing.
            throw new ForemenValidationException(
                    "error.image.too.large", file.getSize(), maxUploadSize.toBytes());
        }

        String objectKey = buildObjectKey(keyNamespace, extension);
        BlobId blobId = BlobId.of(properties.bucket(), objectKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();
        try {
            storage.create(blobInfo, file.getBytes());
        } catch (IOException e) {
            throw new ForemenValidationException("error.image.upload.failed");
        }
        return objectKey;
    }

    @Override
    public String toCdnUrl(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        return properties.cdnBase() + "/" + objectKey;
    }

    @Override
    public void deleteIfOrphan(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        // Requirement 7.8/7.9: re-check the DB (union across all image columns) before deleting so a
        // key still referenced elsewhere is kept.
        if (referenceLookup.isReferenced(objectKey)) {
            return;
        }
        try {
            storage.delete(BlobId.of(properties.bucket(), objectKey));
        } catch (RuntimeException e) {
            // Deleting an already-absent object (or a transient storage error) must not fail the
            // owning write transaction; reconciliation (task 3.5) will reclaim any leftover.
            log.warn("Failed to delete orphan image object {}: {}", objectKey, e.getMessage());
        }
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * List every object key currently present in the configured bucket. Used by the periodic
     * {@link ImageReconciliationJob} (Requirement 7.10) to compute the set of bucket objects to
     * reconcile against the DB-referenced keys. Package-private on purpose: the reconciliation job
     * is the sole intended caller and there is no need to widen the {@link ImageStorage} seam.
     *
     * @return the bucket-relative object keys of all objects in the configured bucket
     */
    Set<String> listObjectKeys() {
        Set<String> keys = new HashSet<>();
        for (Blob blob : storage.list(properties.bucket()).iterateAll()) {
            if (blob != null && blob.getName() != null) {
                keys.add(blob.getName());
            }
        }
        return keys;
    }

    /**
     * Delete a single object from the configured bucket, tolerating an already-absent object or a
     * transient storage error. Used by {@link ImageReconciliationJob} to reclaim an unreferenced
     * bucket object (Requirement 7.10). Unlike {@link #deleteIfOrphan(String)} this performs no DB
     * re-check — the reconciliation job has already established the key is unreferenced.
     *
     * @param objectKey the bucket-relative object key to delete; {@code null}/blank is a no-op
     */
    void deleteObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            storage.delete(BlobId.of(properties.bucket(), objectKey));
        } catch (RuntimeException e) {
            log.warn("Failed to delete bucket object {} during reconciliation: {}",
                    objectKey, e.getMessage());
        }
    }

    /**
     * Build the namespaced object key {@code {namespace}/{uuid}.{ext}} (Requirement 7.3, 7.5). A
     * trailing slash on the supplied namespace is tolerated so callers may pass either
     * {@code "producers"} or {@code "producers/"}.
     */
    private String buildObjectKey(String keyNamespace, String extension) {
        String prefix = keyNamespace == null ? "" : keyNamespace.strip();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        String name = UUID.randomUUID() + "." + extension;
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static DataSize parseMaxUploadSize(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_UPLOAD_SIZE;
        }
        return DataSize.parse(configured.strip());
    }

    /**
     * Build the GCS {@link Storage} client, resolving credentials from
     * {@link ImageStorageProperties.Credentials} when supplied and otherwise falling back to
     * Application Default Credentials.
     */
    private Storage buildStorage() {
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        GoogleCredentials credentials = resolveCredentials();
        if (credentials != null) {
            builder.setCredentials(credentials);
        }
        return builder.build().getService();
    }

    private GoogleCredentials resolveCredentials() {
        ImageStorageProperties.Credentials creds = properties.credentials();
        String location = creds.path() != null && !creds.path().isBlank()
                ? creds.path()
                : creds.location();
        if (location == null || location.isBlank() || "application-default".equalsIgnoreCase(location.strip())) {
            return null; // fall back to Application Default Credentials
        }
        Resource resource = new DefaultResourceLoader().getResource(location.strip());
        try (InputStream in = resource.getInputStream()) {
            return GoogleCredentials.fromStream(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load Google Cloud credentials from '" + location + "'", e);
        }
    }
}
