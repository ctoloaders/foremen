package com.foremen.dao.model;

import java.time.LocalDate;
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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-08 (Requirements 7.1, 7.3): the EstimateAmendment aggregate root — the per-project amendment
 * (aneks) that accumulates execution-stage deltas on top of the signed/frozen estimate WITHOUT
 * mutating it (design §"Backend components"). PROJECT-SCOPED ({@code getProjectIdPath() = "project.id"},
 * R9.1). Maps the {@code estimate_amendments} table (changeset {@code 177-create-estimate-amendments.xml}).
 *
 * <p>At most one amendment per project is {@link AmendmentStatus#OPEN} at a time — the Open_Amendment
 * invariant, backed by the partial unique index {@code ux_amendment_open_per_project}. This spec drives
 * {@link AmendmentStatus#OPEN} (and {@link AmendmentStatus#DELETED}); {@link #effectiveDate} and {@link
 * #signableDocument} are unset here and written only by FOR-06-09 on/after signing (R7.4). The delta
 * lines live in the owned child collection {@link #lines} ({@link AmendmentLineEntity}); an unsigned
 * amendment is a pure overlay that touches no downstream data.
 */
@Entity
@Table(name = "estimate_amendments")
@Getter
@Setter
@NoArgsConstructor
public class EstimateAmendmentEntity extends BaseEntity {

    /** Owner FK to the project, NOT NULL — drives the project-scope filter ({@code "project.id"}, R9.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** 1..N per project; {@code UNIQUE (project_id, seq_no)}. */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** The {@link AmendmentStatus} lifecycle; {@code OPEN} by default (this spec drives OPEN / DELETED). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AmendmentStatus status = AmendmentStatus.OPEN;

    /** Unset in this spec; set only by FOR-06-09 on/after signing (R7.4). */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /** Unset in this spec; set only by FOR-06-09. Nullable FK, {@code ON DELETE SET NULL}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signable_document_id")
    private SignableDocumentEntity signableDocument;

    /** Free text, untranslated (R11.3). */
    @Column(name = "note", length = 1024)
    private String note;

    /**
     * The owned delta lines of this amendment (R7.1) with {@code cascade = ALL} and
     * {@code orphanRemoval = true}, so the service mutates this collection directly and saving the
     * aggregate persists the rows; deleting the amendment discards them (ON DELETE CASCADE).
     */
    @OneToMany(mappedBy = "amendment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AmendmentLineEntity> lines = new ArrayList<>();
}
