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
 * FOR-05-08 (Requirements 7.1, 7.2, 7.3, 7.4): the DocumentMedia child — evidence and attachments
 * (scans, tablet-parafka images, rendered bodies, generic attachments) stored in object storage
 * (FOR-12) with only metadata + {@link #kind} in the DB, mirroring {@code RoomMedia}/{@code
 * ProjectMedia}. Maps the {@code document_media} table (changeset
 * {@code 144-create-document-media.xml}).
 *
 * <p>Project-scoped via {@code document.project.id}. Deleting the media that backs a {@code SIGNED}
 * signature is rejected at the service layer (R7.4).
 */
@Entity
@Table(name = "document_media")
@Getter
@Setter
@NoArgsConstructor
public class DocumentMediaEntity extends BaseEntity {

    /** Owner document FK, NOT NULL; media cannot outlive its document. Scope via document.project.id (R7.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private SignableDocumentEntity document;

    /** Original file name, NOT NULL (R7.1). */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** MIME content type, validated on upload, NOT NULL (R7.3). */
    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    /** File size in bytes, validated on upload, NOT NULL (R7.3). */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /** Object storage uri (FOR-12), NOT NULL (R7.2). */
    @Column(name = "storage_uri", nullable = false, length = 512)
    private String storageUri;

    /** Media kind, NOT NULL (R7.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private DocumentMediaKind kind;

    /** Uploader provenance FK, NOT NULL (R7.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private UserEntity uploadedBy;
}
