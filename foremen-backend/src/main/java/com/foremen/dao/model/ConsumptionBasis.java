package com.foremen.dao.model;

/**
 * The consumption BASIS of a {@code WorkMaterialConsumption} norm (FOR-05-05 amendment #4): whether a
 * norm is applied per work-unit or once per assigned room.
 *
 * <p>The physical quantity of a material line derives from this basis and the cell's single Volume
 * {@code V}:
 * <ul>
 *   <li>{@link #PER_UNIT} — the historical/default basis: physical quantity is {@code norm × V}
 *       (e.g. an area-scaled consumption like paint per m²).</li>
 *   <li>{@link #PER_ROOM} — a FIXED quantity for the WHOLE room regardless of Volume: physical
 *       quantity is {@code norm × 1 = norm}, applied once per assigned room (e.g. 1 shower per
 *       bathroom). Typical of piece/complect ({@code szt} / {@code kpl}) norms.</li>
 * </ul>
 *
 * <p>Stored as a string ({@code @Enumerated(EnumType.STRING)}) both on the catalog consumption row
 * ({@code work_material_consumptions.consumption_basis}) and, copied at assign time so the frozen
 * estimate is self-contained, on the estimate material line
 * ({@code estimate_line_room_materials.consumption_basis}). Serialized to the read model by its enum
 * name; the frontend mirrors it as {@code 'PER_UNIT' | 'PER_ROOM'}. Mirrors the {@link ConsumptionBranch}
 * style.
 */
public enum ConsumptionBasis {
    PER_UNIT,
    PER_ROOM
}
