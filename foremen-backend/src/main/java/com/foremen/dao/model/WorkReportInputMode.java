package com.foremen.dao.model;

/**
 * FOR-06-01 (Requirements 1.2, 2.2) — how a {@link WorkReportRoomEntity} quantity was entered
 * (design §3.2). {@code ABSOLUTE} means {@code quantity} is the reported amount in the work item's
 * measurement unit; {@code PERCENT} means it was entered as a percent in {@code (0, 100]} of the
 * row's Plan_Quantity and resolved to an absolute quantity on save
 * ({@code planQuantity × percent / 100}). Stored on {@code work_report_rooms.input_mode}
 * (VARCHAR(16), NOT NULL) as the enum name.
 */
public enum WorkReportInputMode {
    ABSOLUTE,
    PERCENT
}
