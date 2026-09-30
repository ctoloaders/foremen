package com.foremen.service.estimate.materials;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MaterialsReserveMap;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.estimate.matrix.EstimateMatrixRoomDto;
import com.foremen.service.estimate.matrix.MaterialLineDto;
import com.foremen.service.estimate.matrix.WorkRowDto;
import com.foremen.service.estimate.matrix.WorkTypeGroupDto;

/**
 * The Materials tab read-only projection (FOR-05-05b, design §B2, R1, R2, R3, R5, R6, R7). A pure,
 * stateless {@link Component} that builds a {@link MaterialsListDto} from the shipped kosztorys read
 * model (the {@link EstimateMatrixDto} produced by {@code EstimateMatrixAssembler}) plus the
 * estimate's VAT rate percentage and its persisted {@link MaterialsReserveMap}. It performs no I/O
 * beyond reading the passed-in graph; every quantity, price, and the fulfilment percentage are
 * <strong>derived</strong> from that graph — there is no second quantity or price formula (R7).
 *
 * <p>The projection walks every kosztorys cell's {@link MaterialLineDto}s and keeps only lines with a
 * {@code concreteMaterialId} (Placeholders are skipped, R1.2, R1.3). It groups by branch, then by
 * distinct concrete material id, and folds the per-room as-is aggregate, the per-room brutto, the
 * {@code Row_Total} as-is / effective quantities, the effective row brutto, and the basis
 * classification (a row is {@link ConsumptionBasis#PER_ROOM} only when ALL of its contributing lines
 * are {@code PER_ROOM}). The whole fold lives in pure {@code static} helpers so it is property-testable
 * without Spring.
 *
 * <p>The canonical {@link #effectiveQuantity(BigDecimal, ConsumptionBasis, BigDecimal)} helper is the
 * single place the reserve/ceiling/basis rules live — shared by the row total, the row brutto, and the
 * dashboard effective figures so they never drift (R7.4).
 *
 * <p><strong>Piece-unit rounding (amendment):</strong> when a material's consumption norm unit is the
 * piece unit ({@code szt} / {@code шт}, matched case-insensitively with an optional trailing dot), the
 * two {@code Row_Total} figures — the as-is total and the effective total — are rounded UP to whole
 * numbers (a piece cannot be a fraction). This applies regardless of reserve percent and regardless of
 * consumption basis (PER_UNIT or PER_ROOM). The per-room cell quantities stay EXACT (raw), and prices
 * follow the rounded totals. See {@link MaterialAcc#toRow(List, BigDecimal, BigDecimal)}.
 */
@Component
public class MaterialsListAssembler {

    private static final int VAT_MOVE_POINT_LEFT = 2;

    /** The canonical piece measurement-unit code ({@code name_ru} {@code шт.}, {@code name_pl} {@code szt.}). */
    private static final String PIECE_UNIT_CODE = "szt";

    /**
     * Whether {@code unit} is the piece unit ({@code szt} / {@code шт}). Matches case-insensitively and
     * tolerates a single optional trailing dot (e.g. {@code szt.}). {@code null} ⇒ {@code false}.
     */
    static boolean isPieceUnit(String unit) {
        if (unit == null) {
            return false;
        }
        String normalized = unit.trim();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.equalsIgnoreCase(PIECE_UNIT_CODE);
    }

    /**
     * Round a {@code Row_Total} quantity UP to a whole number when the row's unit is the piece unit
     * (a piece cannot be fractional). Otherwise the quantity is returned unchanged.
     *
     * @param qty  the row total quantity (as-is or effective); {@code null} ⇒ returned unchanged
     * @param unit the row's consumption norm unit
     * @return {@code ceil(qty)} for a piece unit, else {@code qty} unchanged
     */
    static BigDecimal roundUpForPieceUnit(BigDecimal qty, String unit) {
        if (qty != null && isPieceUnit(unit)) {
            return qty.setScale(0, RoundingMode.CEILING);
        }
        return qty;
    }

    /**
     * Assemble the Materials tab read model from the kosztorys matrix, the estimate VAT rate, and the
     * persisted reserve map.
     *
     * @param matrix       the kosztorys read model (source of the resolved {@code Physical_Quantity},
     *                     {@code consumptionBasis}, {@code normUnit}, {@code concreteNet} and
     *                     {@code fillIndicatorPct}); may be {@code null} for a pre-estimate project
     * @param vatRatePct   the estimate's VAT rate as a percentage (e.g. {@code 23.00}); {@code null} ⇒ 0
     * @param reserveMap   the persisted reserve map; {@code null} ⇒ no reserves (identity)
     * @param editable     whether the reserve map may be written (project DRAFT + caller has UPDATE)
     * @return the assembled {@link MaterialsListDto}
     */
    public MaterialsListDto assemble(
            EstimateMatrixDto matrix,
            BigDecimal vatRatePct,
            MaterialsReserveMap reserveMap,
            boolean editable) {
        return project(matrix, vatRatePct, reserveMap, editable);
    }

    // ---------------------------------------------------------------------------------------------
    // Canonical Effective_Quantity helper (R7.4) — the single source of the reserve/ceiling/basis rule
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code Effective_Quantity} for a material row (R7.4): aggregate as-is → apply reserve → ceiling,
     * strictly in that order for a {@link ConsumptionBasis#PER_UNIT} row; identity (no reserve, no
     * ceiling) for a {@link ConsumptionBasis#PER_ROOM} row.
     *
     * <ul>
     *   <li>{@code asIs == null} or {@code asIs == 0} ⇒ {@code 0} — {@code ceil(0) = 0}, never a
     *       spurious whole unit (R12.3).</li>
     *   <li>{@code PER_ROOM} ⇒ {@code asIs} unchanged (R5.2, R4.6).</li>
     *   <li>{@code percent == null} or unset ⇒ identity reserve, i.e. {@code ceil(asIs)} (R4.5).</li>
     *   <li>otherwise {@code ceil(asIs × (1 + percent/100))} (R3.3, R4.3).</li>
     * </ul>
     *
     * @param asIs    the as-is aggregate quantity across rooms
     * @param basis   the row's consumption basis
     * @param percent the material's reserve percent, or {@code null}/unset for identity
     * @return the effective quantity to purchase
     */
    public static BigDecimal effectiveQuantity(BigDecimal asIs, ConsumptionBasis basis, BigDecimal percent) {
        if (asIs == null || asIs.signum() == 0) {
            return BigDecimal.ZERO; // ceil(0) = 0, never a spurious unit (R12.3)
        }
        if (basis == ConsumptionBasis.PER_ROOM) {
            return asIs; // no reserve, no ceiling (R5.2, R4.6)
        }
        BigDecimal pct = percent == null ? BigDecimal.ZERO : percent; // null/unset ⇒ identity (R4.5)
        BigDecimal inflated = asIs.multiply(BigDecimal.ONE.add(pct.movePointLeft(VAT_MOVE_POINT_LEFT)));
        return inflated.setScale(0, RoundingMode.CEILING); // ceiling to the next whole unit
    }

    // ---------------------------------------------------------------------------------------------
    // Net → brutto (R7.2) — net verbatim, VAT applied, never rescaled
    // ---------------------------------------------------------------------------------------------

    /**
     * The brutto unit price for a net unit price: {@code net × (1 + vat/100)} (R2.5, R7.2). The net is
     * used verbatim and never rescaled; a {@code null} net yields a {@code null} brutto (R12.4).
     *
     * @param net        the net unit price (verbatim), or {@code null}
     * @param vatRatePct the VAT rate percentage, or {@code null} ⇒ 0
     * @return the brutto unit price, or {@code null} when {@code net} is {@code null}
     */
    public static BigDecimal bruttoUnitPrice(BigDecimal net, BigDecimal vatRatePct) {
        if (net == null) {
            return null;
        }
        BigDecimal vat = vatRatePct == null ? BigDecimal.ZERO : vatRatePct;
        return net.multiply(BigDecimal.ONE.add(vat.movePointLeft(VAT_MOVE_POINT_LEFT)));
    }

    /**
     * A net + brutto money pair for a quantity at a given net unit price: {@code qty × net} and
     * {@code qty × net × (1 + vat/100)} (R2.5, R7.2). A {@code null} net yields {@link MoneyBrutto#EMPTY}
     * (rendered {@code —}, excluded from totals, R12.4).
     *
     * @param qty        the quantity to price
     * @param net        the net unit price (verbatim), or {@code null}
     * @param vatRatePct the VAT rate percentage, or {@code null} ⇒ 0
     * @return the money pair, or {@link MoneyBrutto#EMPTY} when {@code net} is {@code null}
     */
    public static MoneyBrutto money(BigDecimal qty, BigDecimal net, BigDecimal vatRatePct) {
        if (net == null) {
            return MoneyBrutto.EMPTY; // no fabricated value; excluded from totals (R12.4)
        }
        BigDecimal q = qty == null ? BigDecimal.ZERO : qty;
        BigDecimal netTotal = q.multiply(net);
        BigDecimal bruttoTotal = q.multiply(bruttoUnitPrice(net, vatRatePct));
        return new MoneyBrutto(netTotal, bruttoTotal);
    }

    // ---------------------------------------------------------------------------------------------
    // Pure projection core — property-testable without Spring
    // ---------------------------------------------------------------------------------------------

    /**
     * The pure projection over the kosztorys matrix (R1, R2, R3, R5, R7). Package-visible {@code static}
     * so the property tests can exercise it directly without a Spring context.
     */
    static MaterialsListDto project(
            EstimateMatrixDto matrix,
            BigDecimal vatRatePct,
            MaterialsReserveMap reserveMap,
            boolean editable) {

        Long projectId = matrix == null ? null : matrix.projectId();
        BigDecimal fulfilmentPct = matrix == null ? BigDecimal.ZERO : matrix.fillIndicatorPct();

        List<MaterialsRoomColumnDto> rooms = roomColumns(matrix);
        List<Long> roomOrder = new ArrayList<>();
        for (MaterialsRoomColumnDto room : rooms) {
            roomOrder.add(room.id());
        }

        BigDecimal vat = vatRatePct == null ? BigDecimal.ZERO : vatRatePct;

        Map<MaterialKey, MaterialAcc> accByMaterialKey = foldConcreteLines(matrix);
        List<MaterialBranchGroupDto> branches = emitBranches(accByMaterialKey, roomOrder, vat, reserveMap);

        List<BranchDashboardDto> branchDashboards = new ArrayList<>();
        BranchDashboardDto grandTotal = dashboards(branches, vat, branchDashboards);

        return new MaterialsListDto(
                projectId,
                editable,
                rooms,
                branches,
                branchDashboards,
                grandTotal,
                fulfilmentPct);
    }

    /**
     * Walk every kosztorys cell's concrete material lines into per-material accumulators, in first-seen
     * order so rows/branches are deterministic (R1.2, R1.3, R1.5).
     */
    private static Map<MaterialKey, MaterialAcc> foldConcreteLines(EstimateMatrixDto matrix) {
        Map<MaterialKey, MaterialAcc> accByMaterialKey = new LinkedHashMap<>();
        if (matrix == null || matrix.groups() == null) {
            return accByMaterialKey;
        }
        for (WorkTypeGroupDto group : matrix.groups()) {
            if (group == null || group.rows() == null) {
                continue;
            }
            for (WorkRowDto row : group.rows()) {
                if (row == null || row.cells() == null) {
                    continue;
                }
                for (CellDto cell : row.cells()) {
                    foldCell(cell, accByMaterialKey);
                }
            }
        }
        return accByMaterialKey;
    }

    /**
     * Emit the material rows grouped by branch, preserving the construction → finishing order and the
     * first-seen material order within each branch (R1.5).
     */
    private static List<MaterialBranchGroupDto> emitBranches(
            Map<MaterialKey, MaterialAcc> accByMaterialKey,
            List<Long> roomOrder,
            BigDecimal vat,
            MaterialsReserveMap reserveMap) {
        Map<ConsumptionBranch, List<MaterialRowDto>> rowsByBranch = new LinkedHashMap<>();
        for (ConsumptionBranch branch : ConsumptionBranch.values()) {
            rowsByBranch.put(branch, new ArrayList<>());
        }
        for (MaterialAcc acc : accByMaterialKey.values()) {
            // The reserve map remains keyed by the bare material id (a construction/finishing id-space
            // collision on the reserve map is a separate, far rarer concern left out of this fix).
            MaterialRowDto rowDto = acc.toRow(roomOrder, vat, reservePercent(reserveMap, acc.materialId));
            rowsByBranch.computeIfAbsent(acc.branch, b -> new ArrayList<>()).add(rowDto);
        }
        List<MaterialBranchGroupDto> branches = new ArrayList<>();
        for (Map.Entry<ConsumptionBranch, List<MaterialRowDto>> entry : rowsByBranch.entrySet()) {
            branches.add(new MaterialBranchGroupDto(entry.getKey(), entry.getValue()));
        }
        return branches;
    }

    /**
     * Fold the per-branch and grand-total dashboards (consumption money, as-is vs effective, net +
     * brutto; null-net rows excluded, R6.2, R6.3, R12.4). The per-branch summaries are appended to
     * {@code branchDashboards}; the grand total is returned.
     */
    private static BranchDashboardDto dashboards(
            List<MaterialBranchGroupDto> branches,
            BigDecimal vat,
            List<BranchDashboardDto> branchDashboards) {
        MoneyBrutto grandAsIs = MoneyBrutto.ZERO;
        MoneyBrutto grandEff = MoneyBrutto.ZERO;
        for (MaterialBranchGroupDto branchGroup : branches) {
            MoneyBrutto asIsTotal = MoneyBrutto.ZERO;
            MoneyBrutto effTotal = MoneyBrutto.ZERO;
            for (MaterialRowDto rowDto : branchGroup.rows()) {
                if (rowDto.netUnitPrice() == null) {
                    continue; // null-net rows contribute nothing (R12.4)
                }
                asIsTotal = addMoney(asIsTotal, money(rowDto.asIsTotalQty(), rowDto.netUnitPrice(), vat));
                effTotal = addMoney(effTotal, money(rowDto.effectiveTotalQty(), rowDto.netUnitPrice(), vat));
            }
            branchDashboards.add(new BranchDashboardDto(branchGroup.branch(), asIsTotal, effTotal));
            grandAsIs = addMoney(grandAsIs, asIsTotal);
            grandEff = addMoney(grandEff, effTotal);
        }
        return new BranchDashboardDto(null, grandAsIs, grandEff);
    }

    private static List<MaterialsRoomColumnDto> roomColumns(EstimateMatrixDto matrix) {
        List<MaterialsRoomColumnDto> rooms = new ArrayList<>();
        if (matrix == null || matrix.rooms() == null) {
            return rooms;
        }
        for (EstimateMatrixRoomDto room : matrix.rooms()) {
            rooms.add(new MaterialsRoomColumnDto(room.id(), room.label(), room.roomTypeId(), room.roomTypeName()));
        }
        return rooms;
    }

    /** Fold one cell's concrete material lines into the per-material accumulators (R1.2, R1.3, R2.4, R7.3). */
    private static void foldCell(CellDto cell, Map<MaterialKey, MaterialAcc> accByMaterialKey) {
        if (cell == null || cell.materials() == null) {
            return;
        }
        Long roomId = cell.roomId();
        for (MaterialLineDto line : cell.materials()) {
            if (line == null || line.concreteMaterialId() == null) {
                continue; // Placeholder — excluded (R1.3)
            }
            // Key by the composite (branch, concreteMaterialId): concreteMaterialId is a
            // construction_materials.id for construction lines and a finishing_materials.id for
            // finishing lines — two independent id spaces that must not collide into one row.
            MaterialKey key = new MaterialKey(line.branch(), line.concreteMaterialId());
            MaterialAcc acc = accByMaterialKey.computeIfAbsent(
                    key, k -> new MaterialAcc(line.concreteMaterialId(), line));
            acc.add(roomId, line);
        }
    }

    /**
     * The composite identity of a material row: the {@link ConsumptionBranch} plus the concrete
     * material id. {@code concreteMaterialId} alone is NOT unique across branches (a
     * {@code construction_materials.id} and a {@code finishing_materials.id} can share the same
     * numeric value), so folding by id alone collides two independent materials into one row.
     */
    private record MaterialKey(ConsumptionBranch branch, Long materialId) {
    }

    private static BigDecimal reservePercent(MaterialsReserveMap reserveMap, Long materialId) {
        if (reserveMap == null || reserveMap.byMaterialId() == null) {
            return BigDecimal.ZERO;
        }
        MaterialsReserveMap.ReserveEntry entry = reserveMap.byMaterialId().get(materialId);
        if (entry == null || entry.percent() == null) {
            return BigDecimal.ZERO; // absent key / null percent ⇒ identity (R4.5)
        }
        return entry.percent();
    }

    private static MoneyBrutto addMoney(MoneyBrutto a, MoneyBrutto b) {
        BigDecimal net = nullSafeAdd(a.net(), b.net());
        BigDecimal brutto = nullSafeAdd(a.brutto(), b.brutto());
        return new MoneyBrutto(net, brutto);
    }

    private static BigDecimal nullSafeAdd(BigDecimal a, BigDecimal b) {
        BigDecimal left = a == null ? BigDecimal.ZERO : a;
        BigDecimal right = b == null ? BigDecimal.ZERO : b;
        return left.add(right);
    }

    /**
     * Mutable per-material accumulator (one per distinct (branch, concrete material id)). Captures the row's
     * identity/metadata from its first concrete line and folds the per-room as-is quantities; the
     * basis classification is {@link ConsumptionBasis#PER_ROOM} only while EVERY contributing line is
     * {@code PER_ROOM} (R5.1).
     */
    private static final class MaterialAcc {
        private final Long materialId;
        private final String materialName;
        private final Long typeId;
        private final String typeName;
        private final ConsumptionBranch branch;
        private final BigDecimal net;
        private final String unit;
        private boolean anyPerUnit;
        private final Map<Long, BigDecimal> asIsByRoom = new LinkedHashMap<>();

        private MaterialAcc(Long materialId, MaterialLineDto first) {
            this.materialId = materialId;
            this.materialName = first.concreteMaterialName();
            this.typeId = first.typeId();     // material TYPE (filter attribute, not row identity, fix #2)
            this.typeName = first.typeName();
            this.branch = first.branch();
            this.net = first.concreteNet();
            this.unit = first.normUnit();
        }

        private void add(Long roomId, MaterialLineDto line) {
            if (line.consumptionBasis() != ConsumptionBasis.PER_ROOM) {
                anyPerUnit = true; // any PER_UNIT line ⇒ the whole row is PER_UNIT (R5.1)
            }
            BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
            asIsByRoom.merge(roomId, qty, BigDecimal::add); // Σ Physical_Quantity per room (R2.2, R7.3)
        }

        private ConsumptionBasis basis() {
            // PER_ROOM only when ALL contributing lines are PER_ROOM; otherwise PER_UNIT.
            return anyPerUnit ? ConsumptionBasis.PER_UNIT : ConsumptionBasis.PER_ROOM;
        }

        /**
         * Build the row: per-room cells (raw, EXACT quantities and money) plus the two {@code Row_Total}
         * figures. When the unit is the piece unit ({@code szt} / {@code шт}) both totals — the as-is
         * total and the effective total — are rounded UP to whole numbers (a piece cannot be
         * fractional), regardless of reserve percent or consumption basis; the reserve/ceiling still
         * operates on the RAW as-is total, and the row price follows the rounded effective total. The
         * per-room cells are never rounded (R2.4, R7.3).
         */
        private MaterialRowDto toRow(List<Long> roomOrder, BigDecimal vat, BigDecimal reservePercent) {
            ConsumptionBasis basis = basis();

            List<MaterialRoomCellDto> cells = new ArrayList<>();
            BigDecimal asIsTotal = BigDecimal.ZERO;
            for (Long roomId : roomOrder) {
                BigDecimal cellQty = asIsByRoom.get(roomId);
                if (cellQty == null) {
                    cells.add(new MaterialRoomCellDto(roomId, null, unit, MoneyBrutto.EMPTY)); // empty (R2.3)
                    continue;
                }
                asIsTotal = asIsTotal.add(cellQty);
                cells.add(new MaterialRoomCellDto(roomId, cellQty, unit, money(cellQty, net, vat)));
            }

            // Reserve/ceiling operates on the RAW as-is total; the piece-unit roundup is applied to the
            // two displayed Row_Total figures only (per-room cells stay EXACT).
            BigDecimal displayedAsIs = roundUpForPieceUnit(asIsTotal, unit);
            BigDecimal effectiveRounded =
                    roundUpForPieceUnit(effectiveQuantity(asIsTotal, basis, reservePercent), unit);
            MoneyBrutto rowTotalPrice = money(effectiveRounded, net, vat); // effective qty × unit price (R3.3)

            return new MaterialRowDto(
                    materialId,
                    materialName,
                    typeId,
                    typeName,
                    branch,
                    basis,
                    unit,
                    net,
                    bruttoUnitPrice(net, vat),
                    cells,
                    displayedAsIs,
                    reservePercent,
                    effectiveRounded,
                    rowTotalPrice);
        }
    }
}
