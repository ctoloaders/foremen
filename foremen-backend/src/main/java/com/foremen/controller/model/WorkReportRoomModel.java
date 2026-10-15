package com.foremen.controller.model;

import java.math.BigDecimal;

import com.foremen.dao.model.WorkReportInputMode;

/**
 * FOR-06-01 (Requirements 1.2, 2.1, 2.2, 6.5) — the per-room quantity-split sub-DTO of a work
 * report (design §4.3), used both on the write path (one row per reported room in a create/update
 * request) and on the read path (the resolved rows carried back on a report's DTOs).
 *
 * <p>On the <b>write</b> path a row carries the target {@code roomId}, the entered {@code quantity}
 * (an absolute amount when {@code inputMode == ABSOLUTE}, or a percent in {@code (0, 100]} of the
 * room's Plan_Quantity when {@code inputMode == PERCENT}), and the {@code inputMode}; the service
 * resolves PERCENT rows to an absolute {@code quantity} and snapshots {@code planQuantity} on save
 * (R2.2, R2.6) — a write-path row leaves {@code planQuantity}/{@code roomName}/{@code id} unset.
 *
 * <p>On the <b>read</b> path the row additionally carries its persisted {@code id}, the resolved
 * absolute {@code quantity}, the {@code planQuantity} snapshot (nullable when no plan existed,
 * R2.3), and the localized {@code roomName} (ru → pl → code/label fallback, R12.1).
 *
 * @param id           the persisted {@code WorkReportRoom} id (read path; {@code null} on a write-path row)
 * @param roomId       the target room FK (required on the write path)
 * @param roomName     the room's localized display name (read path; resolved ru → pl → label/code)
 * @param quantity     the entered amount (write) / the resolved absolute amount (read)
 * @param planQuantity the Plan_Quantity snapshot (read path; nullable when no plan exists)
 * @param inputMode    how the quantity was entered ({@code ABSOLUTE} | {@code PERCENT})
 */
public record WorkReportRoomModel(
        Long id,
        Long roomId,
        String roomName,
        BigDecimal quantity,
        BigDecimal planQuantity,
        WorkReportInputMode inputMode) {
}
