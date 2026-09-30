package com.foremen.service.estimate.materials;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;

/**
 * One material row of the Materials tab matrix (FOR-05-05b, design "Read model DTOs"): one row per
 * distinct concrete material (Placeholders excluded, R1.2, R1.3), partitioned by {@link #branch()}
 * (R1.5). The row carries the per-room cells, the two {@code Row_Total} quantities (as-is and
 * effective) and the effective row brutto price (R3.2, R3.3).
 *
 * <p>A {@link ConsumptionBasis#PER_ROOM} row is an {@code Absolute_Per_Room_Material}: reserve and
 * ceiling are suppressed, so {@link #effectiveTotalQty()} equals {@link #asIsTotalQty()} (R5.2, R5.3,
 * R4.6). The row also carries the material TYPE ({@link #typeId()} / {@link #typeName()}) — a
 * filter/grouping attribute distinct from the concrete material identity ({@link #materialId()} /
 * {@link #materialName()}), which stays the row identity (fix #2). A {@code null}
 * {@link #netUnitPrice()} means the chosen product has no net price: quantities
 * still show, but prices render {@code —} and are excluded from money totals (R12.4). Mirrors the
 * frontend {@code MaterialRow}.
 *
 * @param materialId       the concrete material id (row identity within its branch)
 * @param materialName     the concrete material display name (localized at the read layer)
 * @param typeId           the material TYPE id (construction / finishing type) — a filter/grouping
 *                         attribute, distinct from the concrete material identity above (fix #2);
 *                         {@code null} when the type cannot be resolved at read time
 * @param typeName         the material TYPE display name (localized at the read layer), used to filter
 *                         and group rows; distinct from {@link #materialName()}, or {@code null}
 * @param branch           the row's branch (construction / finishing, R1.5)
 * @param basis            the row's consumption basis; {@code PER_ROOM} ⇒ {@code Absolute_Per_Room_Material}
 * @param unit             the material norm's unit code, or {@code null}
 * @param netUnitPrice     the chosen product net unit price (verbatim), or {@code null} (R12.4)
 * @param bruttoUnitPrice  the brutto unit price ({@code netUnitPrice × (1 + vat/100)}), or {@code null}
 * @param cells            one cell per room column, aligned with {@code MaterialsListDto.rooms()}
 * @param asIsTotalQty     the as-is summed {@code Physical_Quantity} across all rooms (R3.2)
 * @param reservePercent   the material's reserve percent from the map (0 when unset)
 * @param effectiveTotalQty the reserve-then-ceiling (basis-aware) effective quantity (R3.2..R3.4, R7.4)
 * @param rowTotalPrice    the effective row brutto price ({@code effectiveTotalQty × unit price}, R3.3);
 *                         {@code MoneyBrutto.EMPTY} when the net is {@code null} (R12.4)
 */
public record MaterialRowDto(
        Long materialId,
        String materialName,
        Long typeId,
        String typeName,
        ConsumptionBranch branch,
        ConsumptionBasis basis,
        String unit,
        BigDecimal netUnitPrice,
        BigDecimal bruttoUnitPrice,
        List<MaterialRoomCellDto> cells,
        BigDecimal asIsTotalQty,
        BigDecimal reservePercent,
        BigDecimal effectiveTotalQty,
        MoneyBrutto rowTotalPrice) {
}
