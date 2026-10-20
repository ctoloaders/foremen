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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-08 (Requirements 7.1, 7.2, 7.3): one delta within an {@link EstimateAmendmentEntity} — a
 * single polymorphic row discriminated by {@link #changeType} ({@link ChangeType}), carrying only the
 * fields relevant to its type (design §"Data model 2"). Maps the {@code amendment_lines} table
 * (changeset {@code 178-create-amendment-lines.xml}).
 *
 * <ul>
 *   <li>{@link ChangeType#SCHEDULE_CHANGE} — populates {@link #scheduleDeltaHalfDays} (signed
 *       half-days) and optionally {@link #workCategory} (null = whole-project schedule shift).</li>
 *   <li>{@link ChangeType#ADDITIONAL_WORK} — populates {@link #workItem}, {@link #room}, {@link
 *       #deltaQuantity} (added volume, positive), {@link #extraUnitPrice} (the full new unit price),
 *       and optionally {@link #description}.</li>
 *   <li>{@link ChangeType#VOLUME_CHANGE} — populates {@link #workItem}, {@link #room}, {@link
 *       #deltaQuantity} (signed), and {@link #extraUnitPrice} (the unit price on the delta volume).</li>
 * </ul>
 *
 * <p>Each line stores a DELTA against the signed baseline, never an absolute value (R7.1), and carries
 * {@link #extraUnitPrice} so the dormant Effective_Date_Seam in {@code WorkReportService} can read it
 * per work item (R7.2). Deleting the owning amendment discards its lines ({@code ON DELETE CASCADE}).
 */
@Entity
@Table(name = "amendment_lines")
@Getter
@Setter
@NoArgsConstructor
public class AmendmentLineEntity extends BaseEntity {

    /** Owner FK to the amendment, NOT NULL; {@code ON DELETE CASCADE}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "amendment_id", nullable = false)
    private EstimateAmendmentEntity amendment;

    /** The {@link ChangeType} discriminator persisted as its enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 32)
    private ChangeType changeType;

    /** ADDITIONAL_WORK / VOLUME_CHANGE target work item; null for SCHEDULE_CHANGE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id")
    private WorkItemEntity workItem;

    /** ADDITIONAL_WORK / VOLUME_CHANGE target room; null for SCHEDULE_CHANGE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private RoomEntity room;

    /** SCHEDULE_CHANGE target category; null = whole-project schedule shift. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_category_id")
    private WorkCategoryEntity workCategory;

    /** Signed volume delta (volume/additional-work); null for SCHEDULE_CHANGE. {@code NUMERIC(12,4)}. */
    @Column(name = "delta_quantity", precision = 12, scale = 4)
    private BigDecimal deltaQuantity;

    /** The new unit price (the Effective_Date_Seam field, R7.2); null for SCHEDULE_CHANGE. {@code NUMERIC(12,2)}. */
    @Column(name = "extra_unit_price", precision = 12, scale = 2)
    private BigDecimal extraUnitPrice;

    /** Signed half-day schedule delta (SCHEDULE_CHANGE); null for cost lines. */
    @Column(name = "schedule_delta_half_days")
    private Integer scheduleDeltaHalfDays;

    /** Free text, untranslated. */
    @Column(name = "description", length = 1024)
    private String description;
}
