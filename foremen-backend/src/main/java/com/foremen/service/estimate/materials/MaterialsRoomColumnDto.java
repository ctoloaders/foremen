package com.foremen.service.estimate.materials;

/**
 * One room column of the Materials tab matrix (FOR-05-05b, design "Read model DTOs"): a thin wrapper
 * over the room id, its optional label and its room type, in the same stable order as the kosztorys
 * room columns (R2.1). Mirrors the frontend room entry {@code { id, label, roomTypeId, roomTypeName }}.
 *
 * <p>The room type ({@link #roomTypeId()} / {@link #roomTypeName()}) is passed through verbatim from
 * the kosztorys {@code EstimateMatrixRoomDto} so the frontend can group/compare columns by type
 * (FOR-05-05b amendment — frontend fixes #3 and #4). It is distinct from the room identity
 * ({@link #id()}); {@code roomTypeId} is {@code null} when the room has no type.
 *
 * @param id           the room id
 * @param label        the room's optional label (nullable)
 * @param roomTypeId   the room type's id, or {@code null} when the room has no type
 * @param roomTypeName the room type's display name (localized at the read layer), or {@code null}
 */
public record MaterialsRoomColumnDto(Long id, String label, Long roomTypeId, String roomTypeName) {
}
