package com.foremen.config.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the shared image-storage configuration properties so the {@code foremen.image-storage}
 * namespace is bound at application startup (FOR-04-17, Requirement 7.11).
 *
 * <p>{@link ImageStorageProperties} carries the bucket name, CDN base URL, credentials source,
 * maximum upload size and the reconciliation schedule — all read from configuration/environment.
 * The concrete {@link com.foremen.service.image.ImageStorage} beans (the GCS implementation, the
 * disabled fallback, the upload controller and the reconciliation job) are wired by the sibling
 * tasks 3.2–3.5 and consume these properties.
 *
 * <p>{@link EnableScheduling} activates Spring's scheduling so the periodic
 * {@link com.foremen.service.image.ImageReconciliationJob} (task 3.5, Requirement 7.10) can run on
 * its configured cron. The job is a no-op unless image storage is configured, and its cron defaults
 * to the disabled sentinel {@code "-"}, so enabling scheduling here has no effect until both a
 * bucket/CDN base and a reconciliation cron are supplied.
 */
@Configuration
@EnableConfigurationProperties(ImageStorageProperties.class)
@EnableScheduling
public class ImageStorageConfig {
}
