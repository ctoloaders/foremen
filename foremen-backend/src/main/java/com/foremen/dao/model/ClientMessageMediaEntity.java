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
 * FOR-06-12 (Requirement 1.3): an attachment of a {@link ClientMessageEntity} — the child table
 * {@code client_message_media} (changeset {@code 185-create-client-message-media.xml}). The bytes
 * live in the object store ({@code ImageStorage.storeObject} under the {@code client-messages}
 * namespace); only the metadata + {@link #kind} are persisted here, mirroring
 * {@link DocumentMediaEntity}. The media service validates content type and the per-kind size cap on
 * upload and resolves a signed download URL per attachment on read (R4).
 */
@Entity
@Table(name = "client_message_media")
@Getter
@Setter
@NoArgsConstructor
public class ClientMessageMediaEntity extends BaseEntity {

    /** Owner message FK, NOT NULL; media cannot outlive its message ({@code ON DELETE CASCADE}, R1.6). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private ClientMessageEntity message;

    /** The uploaded file name, NOT NULL (R1.3). */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** MIME content type, validated on upload, NOT NULL (R4). */
    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    /** File size in bytes, validated against the per-kind cap on upload, NOT NULL (R4). */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Object-storage key ({@code ImageStorage}, FOR-04-17), NOT NULL (R4.1). */
    @Column(name = "storage_uri", nullable = false, length = 512)
    private String storageUri;

    /** The {@link MediaKind} ({@code IMAGE}/{@code VIDEO}/{@code DOCUMENT}) derived from the content type (R1.3, R4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private MediaKind kind;

    /** Who uploaded the attachment, NOT NULL (R1.3). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private UserEntity uploadedBy;
}
