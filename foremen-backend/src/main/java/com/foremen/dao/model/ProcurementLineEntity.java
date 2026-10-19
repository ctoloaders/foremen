package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-03 (Requirements 1.2, 1.4) — one extracted purchased item of a
 * {@link ProcurementDocumentEntity} (design §4.2). Carries the verbatim OCR fields and the per-line
 * proposed match + tier produced by the pure {@code MatchEngine}. Maps the {@code procurement_lines}
 * table (changeset {@code 168-create-procurement-lines.xml}), unique on {@code (document_id, line_no)}.
 *
 * <p>{@link #matchedMaterialId} is a <strong>soft FK by id</strong> (not a JPA {@code @ManyToOne})
 * because the matched {@code Concrete_Material} may be a {@code ConstructionMaterial} or a
 * {@code FinishingMaterial}, projected uniformly through the FOR-05-05b candidate projection. The
 * R1.5 invariant ({@code matchedMaterialId == null ⟺ matchTier == NONE}, and otherwise on the
 * project's estimate) is enforced in {@code ProcurementDocumentService}, not by a DB constraint.
 */
@Entity
@Table(name = "procurement_lines",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_procurement_lines_document_line_no",
           columnNames = {"document_id", "line_no"}))
@Getter
@Setter
@NoArgsConstructor
public class ProcurementLineEntity extends BaseEntity {

    /** Owner FK to the document, NOT NULL; {@code ON DELETE CASCADE} so deleting a document discards its lines (R1.4). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private ProcurementDocumentEntity document;

    /** The sequential 1-based line position within the document (R3.3); {@code >= 1}. */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    /** The verbatim OCR product text (R10.1); not localized. */
    @Column(name = "raw_name", nullable = false, columnDefinition = "text")
    private String rawName;

    /** Parsed quantity; nullable, {@code >= 0}. */
    @Column(name = "quantity", precision = 19, scale = 3)
    private BigDecimal quantity;

    /** The verbatim OCR unit text (R10.1); nullable, not localized. */
    @Column(name = "unit_hint", columnDefinition = "text")
    private String unitHint;

    /** Parsed per-unit price; nullable, {@code >= 0}. */
    @Column(name = "unit_price", precision = 19, scale = 2)
    private BigDecimal unitPrice;

    /** Parsed line total; nullable, {@code >= 0}. */
    @Column(name = "line_total", precision = 19, scale = 2)
    private BigDecimal lineTotal;

    /** The verbatim OCR manufacturer/brand hint (R10.1); nullable. */
    @Column(name = "manufacturer_hint", columnDefinition = "text")
    private String manufacturerHint;

    /** Soft FK (by id) to the matched {@code Concrete_Material}; null for every {@code NONE}-tier line (R1.5). */
    @Column(name = "matched_material_id")
    private Long matchedMaterialId;

    /** The {@link MatchTier} ({@code EXACT}/{@code MANUFACTURER_CATEGORY}/{@code CATEGORY}/{@code NONE}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_tier", nullable = false, length = 32)
    private MatchTier matchTier;

    /** The {@link MatchStatus} ({@code AUTO}/{@code UNMATCHED}/{@code MANUAL_MATCHED}/{@code MANUAL_UNMATCHED}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 16)
    private MatchStatus matchStatus;

    /**
     * FOR-06-04 (Requirement 1.1, design §4.1) — the line's optional unplanned-expense classification.
     * Nullable FK to the FOR-06-00 {@link UnplannedExpenseCategoryEntity} dictionary; settable only on
     * an {@code UNMATCHED}/{@code MANUAL_UNMATCHED} line (R1.3). Backed by {@code unplanned_category_id}
     * (changeset {@code 170-add-procurement-line-unplanned-category.xml}, {@code ON DELETE SET NULL}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unplanned_category_id")
    private UnplannedExpenseCategoryEntity unplannedCategory;
}
