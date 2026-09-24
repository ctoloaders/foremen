package com.foremen.service.estimate.matrix;

/**
 * One room column of the Estimate tab matrix (FOR-05-05, design §B6). Mirrors the frontend
 * {@code EstimateMatrixRoomDto}.
 *
 * @param id           the room id
 * @param label        the room's optional label (nullable)
 * @param roomTypeName the room type's display name (localized at the read layer)
 */
public record EstimateMatrixRoomDto(Long id, String label, String roomTypeName) {
}
