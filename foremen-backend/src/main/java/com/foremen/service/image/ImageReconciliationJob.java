package com.foremen.service.image;

import com.foremen.config.image.ImageStorageConfiguredCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Periodic orphan-reconciliation job for the shared {@link ImageStorage} service (FOR-04-17,
 * Requirement 7.10). It lists every object in the configured Google Cloud Storage bucket, subtracts
 * the union of object keys referenced by any database row (via
 * {@link ImageReferenceLookup#referencedKeys()}, which spans {@code construction_materials.image},
 * {@code material_producers.image} and any future image column), and deletes every bucket object
 * that no database row references — reclaiming objects that the eager
 * {@link GcsImageStorage#deleteIfOrphan(String)} trigger may have missed (e.g. a crash between the
 * bucket write and the DB commit).
 *
 * <p><strong>Only runs when image storage is configured.</strong> The bean is wired only under
 * {@link ImageStorageConfiguredCondition} (bucket + CDN base present) — exactly like
 * {@link GcsImageStorage} — so when storage is not configured the job is not registered at all and
 * is a complete no-op (Requirement 7.12). Because it depends on the concrete {@link GcsImageStorage}
 * (the only implementation that can list/delete bucket objects), the two are always wired together.
 *
 * <p><strong>Scheduling.</strong> The schedule comes from
 * {@code foremen.image-storage.reconciliation.cron} (Requirement 7.10, 7.11), never hardcoded. The
 * property defaults to the {@link Scheduled#CRON_DISABLED "-"} sentinel, so the job is registered
 * but never fires until an operator supplies a real cron expression; a blank/absent schedule
 * therefore disables the job cleanly rather than failing cron parsing. {@link org.springframework
 * .scheduling.annotation.EnableScheduling} is activated on
 * {@link com.foremen.config.image.ImageStorageConfig}.
 */
@Slf4j
@Component
@Conditional(ImageStorageConfiguredCondition.class)
public class ImageReconciliationJob {

    private final GcsImageStorage storage;
    private final ImageReferenceLookup referenceLookup;

    public ImageReconciliationJob(GcsImageStorage storage, ImageReferenceLookup referenceLookup) {
        this.storage = storage;
        this.referenceLookup = referenceLookup;
    }

    /**
     * Reconcile the bucket against the DB-referenced object keys, deleting every unreferenced
     * bucket object. Scheduled from {@code foremen.image-storage.reconciliation.cron}; disabled by
     * default via the {@code "-"} sentinel until a cron is configured.
     */
    @Scheduled(cron = "${foremen.image-storage.reconciliation.cron:-}")
    public void reconcile() {
        Set<String> bucketKeys = storage.listObjectKeys();
        if (bucketKeys.isEmpty()) {
            return;
        }
        Set<String> referencedKeys = referenceLookup.referencedKeys();

        int deleted = 0;
        for (String bucketKey : bucketKeys) {
            if (!referencedKeys.contains(bucketKey)) {
                storage.deleteObject(bucketKey);
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("Image reconciliation deleted {} orphan bucket object(s) out of {} listed",
                    deleted, bucketKeys.size());
        }
    }
}
