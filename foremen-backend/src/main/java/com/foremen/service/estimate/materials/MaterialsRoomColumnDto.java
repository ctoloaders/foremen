package com.foremen.service.estimate.materials;

/**
 * One room column of the Materials tab matrix (FOR-05-05b, design "Read model DTOs"): a thin wrapper
 * over the room id and its optional label, in the same stable order as the kosztorys room columns
 * (R2.1). Mirrors the frontend room entry {@code { id, label }}.
 *
 * @param id    the room id
 * @param label the room's optional label (nullable)
 */
public record MaterialsRoomColumnDto(Long id, String label) {
}
