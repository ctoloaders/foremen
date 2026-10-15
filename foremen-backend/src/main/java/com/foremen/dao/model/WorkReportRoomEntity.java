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
 * FOR-06-01 (Requirements 1.2, 1.6) — the per-room quantity split child of a {@link WorkReportEntity}
 * (design §3.2): one row per {@code (report, room)} carrying the quantity of the report's single work
 * item performed in that room, the input mode used to enter it, and the Plan_Quantity snapshot
 * captured for provenance. Maps the {@code work_report_rooms} table (changeset
 * {@code 164-create-work-report-rooms.xml}).
 *
 * <p>Unique on {@code (report_id, room_id)} — a room appears at most once per report (R2.4). The
 * owner FK is {@code ON DELETE CASCADE} at the DB level and the parent owns the collection with
 * {@code cascade = ALL + orphanRemoval}, so a report's rows are discarded with the report (R1.6).
 */
@Entity
@Table(name = "work_report_rooms",
       uniqueConstraints = @UniqueConstraint(
           name = "ux_work_report_rooms_report_room",
           columnNames = {"report_id", "room_id"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkReportRoomEntity extends BaseEntity {

    /** Owner FK to the parent report, NOT NULL — {@code ON DELETE CASCADE} discards rows with the report (R1.6). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private WorkReportEntity report;

    /** The room the quantity was performed in. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private RoomEntity room;

    /** Resolved ABSOLUTE quantity in the work item's measurement unit (R2.2); service enforces {@code > 0} (R2.5). */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    /** Plan_Quantity snapshot for provenance; null when no plan exists for {@code (workItem, room)} (R2.3, R2.6). */
    @Column(name = "plan_quantity", precision = 19, scale = 3)
    private BigDecimal planQuantity;

    /** The {@link WorkReportInputMode} ({@code ABSOLUTE} | {@code PERCENT}) used to enter the quantity (R1.2). */
    @Enumerated(EnumType.STRING)
    @Column(name = "input_mode", nullable = false)
    private WorkReportInputMode inputMode;
}
