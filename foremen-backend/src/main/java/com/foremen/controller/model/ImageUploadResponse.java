package com.foremen.controller.model;

/**
 * Response of a backend-mediated image upload (FOR-04-17, Requirement 7.2).
 *
 * <p>Returned by {@code ImageController.upload}: {@code objectKey} is the bucket-relative Google
 * Cloud Storage key the caller persists on the owning entity (never the CDN URL, Requirement 7.3),
 * and {@code imageUrl} is the resolved public CDN URL for immediate preview
 * ({@code ImageStorage.toCdnUrl(objectKey)}, Requirement 7.4).
 *
 * @param objectKey the stored bucket-relative object key to persist on the entity's {@code image}
 * @param imageUrl  the resolved public CDN URL for the stored object
 */
public record ImageUploadResponse(String objectKey, String imageUrl) {}
