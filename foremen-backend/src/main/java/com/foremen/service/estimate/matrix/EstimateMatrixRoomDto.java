package com.foremen.service.estimate.matrix;

/**
 * One room column of the Estimate tab matrix (FOR-05-05, design §B6). Mirrors the frontend
 * {@code EstimateMatrixRoomDto}.
 *
 * @param id           the room id
 * @param label        the room's optional label (nullable)
 * @param roomTypeId   the room type's id, used by the frontend to compare a room's type against a
 *                     work's {@code roomTypeIds} attachments (FOR-05-05 amendment #3); {@code null}
 *                     when the room has no type
 * @param roomTypeName the room type's display name (localized at the read layer)
 */
public record EstimateMatrixRoomDto(Long id, String label, Long roomTypeId, String roomTypeName) {
}
