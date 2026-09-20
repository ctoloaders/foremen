package com.foremen.service.image;

import com.foremen.config.image.ImageStorageConfiguredCondition;
import com.foremen.config.image.ImageStorageNotConfiguredCondition;
import com.foremen.exception.ForemenValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Disabled fallback {@link ImageStorage} (FOR-04-17, Requirement 7.12). Active only when image
 * storage is NOT configured (bucket + CDN base absent) via {@link ImageStorageNotConfiguredCondition}
 * — the exact negation of the {@link ImageStorageConfiguredCondition} that gates
 * {@link GcsImageStorage}, so exactly one of the two beans is present in the context.
 *
 * <p>Behaviour when storage is unconfigured:
 * <ul>
 *   <li>{@link #store} rejects the upload gracefully with a client error (HTTP 400 via
 *       {@link ForemenValidationException}, message key {@code error.image.storage.not.configured}),
 *       storing nothing.</li>
 *   <li>{@link #toCdnUrl} returns {@code null} for any key, so entities with a null image keep
 *       reading and listing normally (no CDN URL is resolved).</li>
 *   <li>{@link #deleteIfOrphan} is a no-op — there is no bucket to reclaim from.</li>
 *   <li>{@link #isConfigured()} reports {@code false}.</li>
 * </ul>
 */
@Slf4j
@Component
@Conditional(ImageStorageNotConfiguredCondition.class)
public class DisabledImageStorage implements ImageStorage {

    @Override
    public String store(MultipartFile file, String keyNamespace) {
        // Requirement 7.12: reject uploads gracefully with a client error, storing nothing.
        throw new ForemenValidationException("error.image.storage.not.configured");
    }

    @Override
    public String toCdnUrl(String objectKey) {
        // No storage configured: no key resolves to a CDN URL, so entities with a null (or any)
        // image key keep reading/listing normally.
        return null;
    }

    @Override
    public void deleteIfOrphan(String objectKey) {
        // No bucket configured: nothing to reclaim.
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
