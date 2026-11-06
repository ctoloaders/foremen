package com.foremen.service.room.media;

import java.time.LocalDateTime;

import com.foremen.dao.model.RoomMediaKind;

/**
 * FOR-05-02 (Requirement 10): metadata of a room media file. Carries no binary data and no storage
 * location; bytes are served through {@code GET /api/rooms/{roomId}/media/{mediaId}/content}.
 */
public record RoomMediaDto(
        Long id,
        Long roomId,
        String fileName,
        String contentType,
        Long sizeBytes,
        RoomMediaKind kind,
        LocalDateTime createdDate) {
}
