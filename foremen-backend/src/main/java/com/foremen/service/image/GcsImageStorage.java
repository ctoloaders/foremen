package com.foremen.service.image;

import com.foremen.config.image.ImageStorageConfiguredCondition;
import com.foremen.config.image.ImageStorageProperties;
import com.foremen.exception.ForemenValidationException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    /**
     * Broader content type → extension map for {@link #storeObject}: the image types plus
     * {@code application/pdf} (frozen signing artifacts, scans). An unrecognized type falls back to
     * {@code bin}; the caller is responsible for its own content-type validation.
     */
    private static final Map<String, String> OBJECT_EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "application/pdf", "pdf");

    /** Fallback maximum upload size when {@code foremen.image-storage.max-upload-size} is unset. */
    private static final DataSize DEFAULT_MAX_UPLOAD_SIZE = DataSize.ofMegabytes(5);

    private final ImageStorageProperties properties;
    private final ImageReferenceLookup referenceLookup;
    private final DataSize maxUploadSize;

    /**
     * The lazily-built GCS client. Guarded by {@link #storageLock} and published through
     * {@link #storage()}. It is intentionally NOT built in a {@code @PostConstruct}: resolving GCS
     * credentials can block indefinitely (e.g. Application Default Credentials probing a
     * metadata/token endpoint, or a wedged credential-file mount in local Docker), and doing that at
     * bean-init time would hang the whole Spring context startup — the backend would never bind its
     * HTTP port. Building on first actual use keeps the application boot fast and resilient: image
     * storage only touches GCS when a feature that needs it is exercised, and a credential problem
     * surfaces there as a handled error rather than a startup hang.
     */
    private volatile Storage storage;

    private final Object storageLock = new Object();

    public GcsImageStorage(ImageStorageProperties properties, ImageReferenceLookup referenceLookup) {
        this.properties = properties;
        this.referenceLookup = referenceLookup;
        this.maxUploadSize = parseMaxUploadSize(properties.maxUploadSize());
    }

    /**
     * Return the GCS client, building it on first use (double-checked locking). See {@link #storage}
     * for why the client is created lazily rather than in a {@code @PostConstruct}.
     */
    private Storage storage() {
        Storage local = this.storage;
        if (local == null) {
            synchronized (storageLock) {
                local = this.storage;
                if (local == null) {
                    local = buildStorage();
                    this.storage = local;
                }
            }
        }
        return local;
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

        return upload(file, keyNamespace, extension, contentType);
    }

    @Override
    public String storeObject(MultipartFile file, String keyNamespace) {
        if (file == null || file.isEmpty()) {
            throw new ForemenValidationException("error.image.empty");
        }
        if (file.getSize() > maxUploadSize.toBytes()) {
            throw new ForemenValidationException(
                    "error.image.too.large", file.getSize(), maxUploadSize.toBytes());
        }
        String contentType = file.getContentType();
        // No image-only allow-list here — the caller validates its own permitted types. Resolve a
        // sensible extension, defaulting to "bin" for an unrecognized content type.
        String extension = contentType == null
                ? "bin"
                : OBJECT_EXTENSIONS.getOrDefault(contentType.toLowerCase(), "bin");
        return upload(file, keyNamespace, extension, contentType);
    }

    /** Shared upload: build the namespaced key, create the blob, map an I/O failure to a 400. */
    private String upload(MultipartFile file, String keyNamespace, String extension, String contentType) {
        String objectKey = buildObjectKey(keyNamespace, extension);
        BlobId blobId = BlobId.of(properties.bucket(), objectKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType(contentType).build();
        try {
            storage().create(blobInfo, file.getBytes());
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

    /**
     * Build a short-lived V4 signed GET URL for a private object (FOR-05-08). Signing is keyless:
     * when a signer service account is configured, we sign through {@link ImpersonatedCredentials}
     * (which calls the IAM {@code signBlob} service), so no private key is ever held by the backend.
     * When no signer SA is configured, we sign with the storage client's own credentials — which on
     * a GCE/Cloud Run runtime is the attached service account (also keyless, via metadata).
     */
    @Override
    public String signedDownloadUrl(String objectKey, long ttlSeconds) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(properties.bucket(), objectKey)).build();
        long ttl = ttlSeconds > 0 ? ttlSeconds : 600L;

        Storage.SignUrlOption[] options = buildSignOptions();
        URL url = storage().signUrl(blobInfo, ttl, TimeUnit.SECONDS, options);
        return url == null ? null : url.toString();
    }

    /**
     * The V4 signing options: always V4; when a signer SA is configured, sign through the IAM
     * {@code signBlob} service via {@link ImpersonatedCredentials} (keyless). Returned as a fresh
     * array per call so the impersonated token can refresh.
     */
    private Storage.SignUrlOption[] buildSignOptions() {
        String signer = properties.signerServiceAccount();
        if (signer == null || signer.isBlank()) {
            // Sign with the client's own credentials (attached SA on GCE/Cloud Run — keyless).
            return new Storage.SignUrlOption[] {
                    Storage.SignUrlOption.withV4Signature(),
                    Storage.SignUrlOption.httpMethod(HttpMethod.GET),
            };
        }
        try {
            ImpersonatedCredentials signerCredentials = ImpersonatedCredentials.create(
                    GoogleCredentials.getApplicationDefault(),
                    signer.strip(),
                    null,
                    List.of("https://www.googleapis.com/auth/devstorage.read_only"),
                    300);
            return new Storage.SignUrlOption[] {
                    Storage.SignUrlOption.withV4Signature(),
                    Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                    Storage.SignUrlOption.signWith(signerCredentials),
            };
        } catch (IOException e) {
            throw new ForemenValidationException("error.image.upload.failed");
        }
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
            storage().delete(BlobId.of(properties.bucket(), objectKey));
        } catch (RuntimeException e) {
            // Deleting an already-absent object (or a transient storage error) must not fail the
            // owning write transaction; reconciliation (task 3.5) will reclaim any leftover.
            log.warn("Failed to delete orphan image object {}: {}", objectKey, e.getMessage());
        }
    }

    @Override
    public byte[] readObject(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        Blob blob = storage().get(BlobId.of(properties.bucket(), objectKey));
        if (blob == null || !blob.exists()) {
            // A configured bucket with no object under the key is a client-visible error.
            throw new ForemenValidationException("error.image.not.found", objectKey);
        }
        return blob.getContent();
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
        for (Blob blob : storage().list(properties.bucket()).iterateAll()) {
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
            storage().delete(BlobId.of(properties.bucket(), objectKey));
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
