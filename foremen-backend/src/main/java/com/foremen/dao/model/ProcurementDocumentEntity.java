package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-03 (Requirements 1.1, 1.3) — the ProcurementDocument persistence root (design §4.1): the
 * project-scoped anchor an authorized user (FINANCIER / MANAGER / FOREMAN) creates when uploading a
 * primary procurement document (a receipt or invoice, image or PDF) on an {@code ACTIVE} project.
 * Its per-line extracted items live in the owned child collection {@link #lines}
 * ({@link ProcurementLineEntity}). Maps the {@code procurement_documents} table (changeset
 * {@code 167-create-procurement-documents.xml}).
 *
 * <p>This spec owns intake + automatic matching only. The document carries the raw upload key, the
 * processed white-background rendition key, the parse-derived supplier/total, and the OCR/parse
 * lifecycle {@link #status}. For a multi-page PDF the processed rendition is a per-page set keyed
 * from {@link #processedObjectKey}; where cleanup falls back for a page its processed rendition
 * equals its oriented original.
 */
@Entity
@Table(name = "procurement_documents")
@Getter
@Setter
@NoArgsConstructor
public class ProcurementDocumentEntity extends BaseEntity {

    /** Owner FK to the project, NOT NULL — drives the project-scope filter ({@code "project.id"}, R7.2). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** Who uploaded the document (R2.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private UserEntity uploadedBy;

    /** ImageStorage object key of the raw upload exactly as received (R2.1). */
    @Column(name = "raw_object_key", nullable = false, length = 512)
    private String rawObjectKey;

    /** ImageStorage object key of the white-background rendition OCR runs on; null until cleanup runs (R3.0). */
    @Column(name = "processed_object_key", length = 512)
    private String processedObjectKey;

    /** The uploaded content type (image/png, image/jpeg, image/webp, application/pdf). */
    @Column(name = "source_mime_type", nullable = false, length = 100)
    private String sourceMimeType;

    /** Document-level supplier/store name from the parse; nullable. */
    @Column(name = "supplier_name", columnDefinition = "text")
    private String supplierName;

    /** Document-level total from the parse; nullable, {@code >= 0}. */
    @Column(name = "document_total", precision = 19, scale = 2)
    private BigDecimal documentTotal;

    /** The {@link ProcessingStatus} ({@code UPLOADED}/{@code PROCESSING}/{@code PARSED}/{@code FAILED}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProcessingStatus status;

    /** When the document was uploaded (R2.5). {@code TIMESTAMP WITH TIME ZONE} → {@link OffsetDateTime}. */
    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;

    /** When the parse succeeded; nullable until {@code PARSED}. */
    @Column(name = "parsed_at")
    private OffsetDateTime parsedAt;

    /** A stable failure code surfaced to the UI on {@code FAILED}; nullable. */
    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    /**
     * The owned per-line extracted items (one row per purchased item, R1.2) ordered by
     * {@code lineNo ASC}, with {@code cascade = ALL} and {@code orphanRemoval = true}, so the service
     * mutates this collection directly and saving the aggregate persists the rows; deleting the
     * document discards them (R1.4, {@code ON DELETE CASCADE}).
     */
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<ProcurementLineEntity> lines = new ArrayList<>();
}
