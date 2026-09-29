package com.foremen.service.estimate.materials;

import java.math.BigDecimal;

/**
 * One {@code (material, room)} intersection of the Materials tab matrix (FOR-05-05b, design "Read
 * model DTOs"). Carries the aggregated as-is {@code Physical_Quantity} for that material in that room
 * (the sum of the kosztorys resolved {@code MaterialLineDto.quantity} over every cell of the room that
 * uses the material, R2.2, R2.4, R7.1, R7.3) with its unit, and the per-room brutto price
 * ({@code asIsCellQty × bruttoUnitPrice}, R2.5). A room the material is not consumed in yields a
 * {@code null} quantity (rendered as an empty placeholder, R2.3). Reserve and roundup are NOT applied
 * at the cell level (R2.4). Mirrors the frontend {@code MaterialRoomCell}.
 *
 * @param roomId   the room (column) id
 * @param quantity the aggregated as-is quantity for {@code (material, room)}; {@code null} ⇒ empty (R2.3)
 * @param unit     the material norm's unit code (from {@code MaterialLineDto.normUnit}), or {@code null}
 * @param price    the per-room brutto price pair; {@code MoneyBrutto.EMPTY} when the net is {@code null}
 */
public record MaterialRoomCellDto(
        Long roomId,
        BigDecimal quantity,
        String unit,
        MoneyBrutto price) {
}
