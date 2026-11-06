package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-02 (Requirement 10.1): a file attached to a room (AI source crop or manual upload), modelled
 * on {@link DocumentMediaEntity}. Bytes live in object storage; only metadata + {@link #kind} is kept
 * in the DB. Maps {@code room_media} (changeset {@code 214-create-room-media.xml}). Project-scoped via
 * {@code room.project.id}.
 */
@Entity
@Table(name = "room_media")
@Getter
@Setter
@NoArgsConstructor
public class RoomMediaEntity extends BaseEntity {

    /** Owner room FK, NOT NULL; media cannot outlive its room (ON DELETE CASCADE). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private RoomEntity room;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /** Object storage uri, NOT NULL. */
    @Column(name = "storage_uri", nullable = false, length = 512)
    private String storageUri;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private RoomMediaKind kind;

    /** Uploader provenance FK, NOT NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private UserEntity uploadedBy;
}
