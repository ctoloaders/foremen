package com.foremen.service.estimate.materials;

// Feature: for-05-05b-list-of-materials

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MaterialsReserveMap;
import com.foremen.dao.model.MaterialsReserveMap.ReserveEntry;
import com.foremen.service.estimate.matrix.BranchSubtotals;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.estimate.matrix.EstimateMatrixRoomDto;
import com.foremen.service.estimate.matrix.FillState;
import com.foremen.service.estimate.matrix.MaterialLineDto;
import com.foremen.service.estimate.matrix.MoneyRange;
import com.foremen.service.estimate.matrix.WorkRowDto;
import com.foremen.service.estimate.matrix.WorkTypeGroupDto;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link MaterialsListAssembler} (FOR-05-05b) — the pure, stateless,
 * read-only projection over the shipped kosztorys read model ({@link EstimateMatrixDto}) into the
 * Materials tab read model ({@link MaterialsListDto}).
 *
 * <p>The assembler is exercised through its package-visible {@code static} core
 * ({@link MaterialsListAssembler#project} plus the {@link MaterialsListAssembler#effectiveQuantity},
 * {@link MaterialsListAssembler#money} and {@link MaterialsListAssembler#bruttoUnitPrice} helpers) with
 * NO Spring context and NO database: kosztorys matrices are generated as in-memory
 * {@link EstimateMatrixDto}s built from cells carrying any mix of concrete / Placeholder material
 * lines, with materials repeated across cells and rooms. Each property recomputes its expectation with
 * an INDEPENDENT oracle rather than reusing the assembler's own logic.
 *
 * <p>Covers four properties:
 * <ul>
 *   <li><b>Property 1</b> — materials rows are exactly the distinct concrete materials, partitioned by
 *       branch (R1.2, R1.3, R1.5).</li>
 *   <li><b>Property 2</b> — per-room quantity is the summed resolved {@code Physical_Quantity} and the
 *       cell price is {@code net × VAT} with the net used verbatim (R2.2, R2.3, R2.4, R2.5, R7.1,
 *       R7.2, R7.3).</li>
 *   <li><b>Property 3</b> — {@code Effective_Quantity} is reserve-then-ceiling for {@code PER_UNIT} and
 *       identity for {@code PER_ROOM} (R3.2..R3.4, R4.3, R4.5, R4.6, R4.7, R5.1..R5.3, R7.4,
 *       R12.3).</li>
 *   <li><b>Property 7</b> — dashboard totals sum the per-material contributions per branch and
 *       project-wide, as-is vs effective, net vs brutto, excluding null-priced rows (R6.2, R6.3, R6.4,
 *       R12.4).</li>
 * </ul>
 */
class MaterialsListAssemblerPropertyTest {

    private static final int VAT_SCALE = 2;

    // Bounded universes so concrete material ids collide frequently across cells/rooms (exercising
    // the distinct-material fold) and rooms/works repeat.
    private static final Long[] CONCRETE_MATERIAL_IDS = {10L, 11L, 12L};
    private static final Long[] ROOM_IDS = {100L, 101L, 102L};

    // =============================================================================================
    // Property 1: Materials rows are exactly the distinct concrete materials, partitioned by branch.
    // Validates: Requirements 1.2, 1.3, 1.5
    // =============================================================================================

    @Property(tries = 100)
    @Tag("Feature: for-05-05b-list-of-materials, Property 1: Materials rows are exactly the distinct concrete materials, partitioned by branch")
    void rowsAreExactlyTheDistinctConcreteMaterialsPartitionedByBranch(
            @ForAll("matrices") EstimateMatrixDto matrix,
            @ForAll("vatRates") BigDecimal vat) {

        MaterialsListDto dto = MaterialsListAssembler.project(matrix, vat, null, false);

        // ---- Oracle: the distinct (branch, concreteMaterialId) pairs present on concrete lines. ----
        Map<Long, ConsumptionBranch> expectedBranchByMaterial = new LinkedHashMap<>();
        for (MaterialLineDto line : concreteLines(matrix)) {
            expectedBranchByMaterial.putIfAbsent(line.concreteMaterialId(), line.branch());
        }

        // Collect every emitted row and the branch group it sits in.
        Map<Long, ConsumptionBranch> emittedBranchByMaterial = new LinkedHashMap<>();
        List<Long> emittedRowMaterialIds = new ArrayList<>();
        Set<ConsumptionBranch> seenBranchGroups = new LinkedHashSet<>();

        for (MaterialBranchGroupDto group : dto.branches()) {
            // Every branch group is a real branch and appears at most once (branch groups partition).
            assertThat(seenBranchGroups.add(group.branch()))
                    .as("branch group %s appears exactly once", group.branch())
                    .isTrue();
            for (MaterialRowDto row : group.rows()) {
                emittedRowMaterialIds.add(row.materialId());
                emittedBranchByMaterial.put(row.materialId(), group.branch());
                // A row's own branch field agrees with the group it is partitioned into (R1.5).
                assertThat(row.branch())
                        .as("row %s branch matches its group", row.materialId())
                        .isEqualTo(group.branch());
            }
        }

        // Exactly one row per distinct concrete material id — no duplicates (R1.2), and the same
        // product repeated across cells/rooms folds into a single row.
        assertThat(emittedRowMaterialIds)
                .as("no duplicate rows: exactly one row per distinct concrete material id")
                .doesNotHaveDuplicates();

        // The emitted rows are EXACTLY the distinct concrete materials — no Placeholder-only row
        // (R1.3), none missing (R1.2).
        assertThat(new LinkedHashSet<>(emittedRowMaterialIds))
                .as("rows are exactly the distinct concrete material ids (Placeholders excluded)")
                .isEqualTo(expectedBranchByMaterial.keySet());

        // Each material sits in the branch of its first concrete line (R1.5).
        assertThat(emittedBranchByMaterial)
                .as("each material is grouped under its (first) branch")
                .isEqualTo(expectedBranchByMaterial);

        // The branch groups partition the rows: Σ group sizes == total distinct materials.
        int totalRows = dto.branches().stream().mapToInt(g -> g.rows().size()).sum();
        assertThat(totalRows)
                .as("branch groups partition the rows (no row lost or double-counted)")
                .isEqualTo(expectedBranchByMaterial.size());
    }

    // =============================================================================================
    // Property 2: Per-room quantity is the summed resolved Physical_Quantity and price is net×VAT.
    // Validates: Requirements 2.2, 2.3, 2.4, 2.5, 7.1, 7.2, 7.3
    // =============================================================================================

    @Property(tries = 100)
    @Tag("Feature: for-05-05b-list-of-materials, Property 2: Per-room quantity is the summed resolved Physical_Quantity and price is net×VAT")
    void perRoomQuantityIsSummedPhysicalQuantityAndPriceIsNetTimesVat(
            @ForAll("matrices") EstimateMatrixDto matrix,
            @ForAll("vatRates") BigDecimal vat) {

        // A non-null reserve keeps cells reserve-free regardless (R2.4): supply a real map.
        MaterialsReserveMap reserveMap = reserveMapFor(matrix);
        MaterialsListDto dto = MaterialsListAssembler.project(matrix, vat, reserveMap, false);

        List<Long> roomOrder = dto.rooms().stream().map(MaterialsRoomColumnDto::id).toList();

        for (MaterialBranchGroupDto group : dto.branches()) {
            for (MaterialRowDto row : group.rows()) {
                BigDecimal net = row.netUnitPrice();

                // ---- Oracle: Σ MaterialLineDto.quantity over every cell of each room using this
                // material (the resolved, override/basis-aware Physical_Quantity, R7.1, R7.3). ----
                Map<Long, BigDecimal> asIsByRoom = oracleAsIsByRoom(matrix, row.materialId());

                // The cells align with the room columns, in order (R2.1).
                assertThat(row.cells()).hasSameSizeAs(roomOrder);
                for (int i = 0; i < roomOrder.size(); i++) {
                    Long roomId = roomOrder.get(i);
                    MaterialRoomCellDto cell = row.cells().get(i);
                    assertThat(cell.roomId()).isEqualTo(roomId);

                    BigDecimal expectedQty = asIsByRoom.get(roomId);
                    if (expectedQty == null) {
                        // Not consumed in the room ⇒ empty placeholder (R2.3): null qty, empty price.
                        assertThat(cell.quantity())
                                .as("room %s not consumed ⇒ empty quantity", roomId)
                                .isNull();
                        assertThat(cell.price().net())
                                .as("room %s not consumed ⇒ empty net", roomId)
                                .isNull();
                        assertThat(cell.price().brutto())
                                .as("room %s not consumed ⇒ empty brutto", roomId)
                                .isNull();
                        continue;
                    }

                    // Cell qty is the summed resolved Physical_Quantity, reserve/roundup NOT applied
                    // at cell level (R2.2, R2.4, R7.3).
                    assertThat(cell.quantity())
                            .as("room %s cell qty is Σ resolved Physical_Quantity", roomId)
                            .isEqualByComparingTo(expectedQty);

                    if (net == null) {
                        // null net ⇒ price rendered as empty and excluded from totals (R12.4).
                        assertThat(cell.price().net()).isNull();
                        assertThat(cell.price().brutto()).isNull();
                    } else {
                        // Cell brutto = asIsCellQty × net × (1 + vat/100); net used verbatim (R2.5,
                        // R7.2 — never rescaled).
                        BigDecimal expectedNetMoney = expectedQty.multiply(net);
                        BigDecimal expectedBrutto = expectedQty
                                .multiply(net.multiply(BigDecimal.ONE.add(vat.movePointLeft(VAT_SCALE))));
                        assertThat(cell.price().net())
                                .as("room %s cell net = qty × net verbatim", roomId)
                                .isEqualByComparingTo(expectedNetMoney);
                        assertThat(cell.price().brutto())
                                .as("room %s cell brutto = qty × net × (1 + vat/100)", roomId)
                                .isEqualByComparingTo(expectedBrutto);
                    }
                }

                // Row as-is total is the sum of the (present) per-room cell quantities (R3.2), and
                // reserve is never folded into the cells (R2.4).
                BigDecimal expectedAsIsTotal = asIsByRoom.values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(row.asIsTotalQty())
                        .as("row as-is total is the Σ of per-room cell quantities")
                        .isEqualByComparingTo(expectedAsIsTotal);
            }
        }
    }

    // =============================================================================================
    // Property 3: Effective_Quantity is reserve-then-ceiling for PER_UNIT and identity for PER_ROOM.
    // Validates: Requirements 3.2, 3.3, 3.4, 4.3, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 7.4, 12.3
    // =============================================================================================

    @Property(tries = 100)
    @Tag("Feature: for-05-05b-list-of-materials, Property 3: Effective_Quantity is reserve-then-ceiling for PER_UNIT and identity for PER_ROOM")
    void effectiveQuantityIsReserveThenCeilingForPerUnitAndIdentityForPerRoom(
            @ForAll("quantities") BigDecimal asIs,
            @ForAll ConsumptionBasis basis,
            @ForAll("percentsOrNull") BigDecimal percent) {

        BigDecimal effective = MaterialsListAssembler.effectiveQuantity(asIs, basis, percent);

        if (asIs.signum() == 0) {
            // q = 0 ⇒ 0, never a spurious whole unit (R12.3), regardless of basis or percent.
            assertThat(effective)
                    .as("as-is 0 ⇒ effective 0 (no spurious ceiling)")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            return;
        }

        if (basis == ConsumptionBasis.PER_ROOM) {
            // PER_ROOM ⇒ identity: no reserve, no ceiling (R5.2, R4.6).
            assertThat(effective)
                    .as("PER_ROOM ⇒ effective == as-is (identity)")
                    .isEqualByComparingTo(asIs);
            return;
        }

        // PER_UNIT: aggregate → reserve → ceiling, strictly in that order (R7.4).
        BigDecimal pct = percent == null ? BigDecimal.ZERO : percent; // null/unset ⇒ identity (R4.5)
        BigDecimal inflated = asIs.multiply(BigDecimal.ONE.add(pct.movePointLeft(VAT_SCALE)));
        BigDecimal expected = inflated.setScale(0, RoundingMode.CEILING);
        assertThat(effective)
                .as("PER_UNIT ⇒ ceil(asIs × (1 + pct/100))")
                .isEqualByComparingTo(expected);

        // Order matters: reserve-then-ceiling is NEVER ceiling-then-reserve. When they differ, the
        // assembler must equal the former.
        BigDecimal ceilingThenReserve = asIs.setScale(0, RoundingMode.CEILING)
                .multiply(BigDecimal.ONE.add(pct.movePointLeft(VAT_SCALE)))
                .setScale(0, RoundingMode.CEILING);
        if (expected.compareTo(ceilingThenReserve) != 0) {
            assertThat(effective)
                    .as("order is aggregate→reserve→ceiling, never ceiling-before-reserve")
                    .isNotEqualByComparingTo(ceilingThenReserve);
        }

        // p = 0 / unset ⇒ ceil(asIs) (R4.5).
        assertThat(MaterialsListAssembler.effectiveQuantity(asIs, ConsumptionBasis.PER_UNIT, null))
                .as("unset percent ⇒ ceil(asIs)")
                .isEqualByComparingTo(asIs.setScale(0, RoundingMode.CEILING));
        assertThat(MaterialsListAssembler.effectiveQuantity(asIs, ConsumptionBasis.PER_UNIT, BigDecimal.ZERO))
                .as("zero percent ⇒ ceil(asIs)")
                .isEqualByComparingTo(asIs.setScale(0, RoundingMode.CEILING));

        // Effective is always ≥ as-is for PER_UNIT (reserve + ceiling only grow it).
        assertThat(effective)
                .as("PER_UNIT effective ≥ as-is")
                .isGreaterThanOrEqualTo(asIs);

        // The row brutto is effectiveQty × brutto unit price (R3.3, R7.4).
        BigDecimal net = new BigDecimal("12.50");
        BigDecimal vat = new BigDecimal("23.00");
        MoneyBrutto rowTotal = MaterialsListAssembler.money(effective, net, vat);
        BigDecimal expectedBrutto = effective
                .multiply(net.multiply(BigDecimal.ONE.add(vat.movePointLeft(VAT_SCALE))));
        assertThat(rowTotal.brutto())
                .as("row brutto = effectiveQty × net × (1 + vat/100)")
                .isEqualByComparingTo(expectedBrutto);
        assertThat(rowTotal.net())
                .as("row net = effectiveQty × net")
                .isEqualByComparingTo(effective.multiply(net));
    }

    // =============================================================================================
    // Property 7: Dashboard totals sum per-material contributions per branch and project-wide,
    //             as-is vs effective, net vs brutto, excluding null-priced.
    // Validates: Requirements 6.2, 6.3, 6.4, 12.4
    // =============================================================================================

    @Property(tries = 100)
    @Tag("Feature: for-05-05b-list-of-materials, Property 7: Dashboard totals sum per-material contributions per branch and project-wide, as-is vs effective, net vs brutto, excluding null-priced")
    void dashboardTotalsSumPerMaterialContributionsPerBranchAndProjectWide(
            @ForAll("matrices") EstimateMatrixDto matrix,
            @ForAll("vatRates") BigDecimal vat,
            @ForAll("percentByMaterial") Map<Long, BigDecimal> percents) {

        MaterialsReserveMap reserveMap = reserveMapFromPercents(percents);
        MaterialsListDto dto = MaterialsListAssembler.project(matrix, vat, reserveMap, false);

        BigDecimal vatFactor = BigDecimal.ONE.add(vat.movePointLeft(VAT_SCALE));

        MoneyBrutto oracleGrandAsIs = MoneyBrutto.ZERO;
        MoneyBrutto oracleGrandEff = MoneyBrutto.ZERO;

        // ---- Per-branch dashboard equals the Σ over that branch's rows of the row's contribution. ----
        assertThat(dto.branchDashboards()).hasSameSizeAs(dto.branches());
        for (int b = 0; b < dto.branches().size(); b++) {
            MaterialBranchGroupDto group = dto.branches().get(b);
            BranchDashboardDto dashboard = dto.branchDashboards().get(b);
            assertThat(dashboard.branch())
                    .as("branch dashboard aligns with its branch group")
                    .isEqualTo(group.branch());

            BigDecimal asIsNet = BigDecimal.ZERO;
            BigDecimal asIsBrutto = BigDecimal.ZERO;
            BigDecimal effNet = BigDecimal.ZERO;
            BigDecimal effBrutto = BigDecimal.ZERO;

            for (MaterialRowDto row : group.rows()) {
                if (row.netUnitPrice() == null) {
                    continue; // null-net row contributes nothing (R12.4)
                }
                BigDecimal net = row.netUnitPrice();
                asIsNet = asIsNet.add(row.asIsTotalQty().multiply(net));
                asIsBrutto = asIsBrutto.add(row.asIsTotalQty().multiply(net).multiply(vatFactor));
                effNet = effNet.add(row.effectiveTotalQty().multiply(net));
                effBrutto = effBrutto.add(row.effectiveTotalQty().multiply(net).multiply(vatFactor));
            }

            assertThat(dashboard.asIsTotal().net())
                    .as("branch %s as-is net", group.branch())
                    .isEqualByComparingTo(asIsNet);
            assertThat(dashboard.asIsTotal().brutto())
                    .as("branch %s as-is brutto", group.branch())
                    .isEqualByComparingTo(asIsBrutto);
            assertThat(dashboard.effectiveTotal().net())
                    .as("branch %s effective net", group.branch())
                    .isEqualByComparingTo(effNet);
            assertThat(dashboard.effectiveTotal().brutto())
                    .as("branch %s effective brutto", group.branch())
                    .isEqualByComparingTo(effBrutto);

            oracleGrandAsIs = new MoneyBrutto(
                    oracleGrandAsIs.net().add(asIsNet), oracleGrandAsIs.brutto().add(asIsBrutto));
            oracleGrandEff = new MoneyBrutto(
                    oracleGrandEff.net().add(effNet), oracleGrandEff.brutto().add(effBrutto));
        }

        // ---- Grand total equals the Σ of the per-branch totals (R6.3). ----
        assertThat(dto.grandTotal().branch())
                .as("grand total is the project-wide summary (null branch)")
                .isNull();
        assertThat(dto.grandTotal().asIsTotal().net())
                .as("grand as-is net = Σ per-branch as-is net")
                .isEqualByComparingTo(oracleGrandAsIs.net());
        assertThat(dto.grandTotal().asIsTotal().brutto())
                .as("grand as-is brutto = Σ per-branch as-is brutto")
                .isEqualByComparingTo(oracleGrandAsIs.brutto());
        assertThat(dto.grandTotal().effectiveTotal().net())
                .as("grand effective net = Σ per-branch effective net")
                .isEqualByComparingTo(oracleGrandEff.net());
        assertThat(dto.grandTotal().effectiveTotal().brutto())
                .as("grand effective brutto = Σ per-branch effective brutto")
                .isEqualByComparingTo(oracleGrandEff.brutto());
    }

    // =============================================================================================
    // Oracles (independent of the assembler internals).
    // =============================================================================================

    /** Every concrete material line across the whole matrix, in walk order. */
    private static List<MaterialLineDto> concreteLines(EstimateMatrixDto matrix) {
        List<MaterialLineDto> lines = new ArrayList<>();
        for (WorkTypeGroupDto group : matrix.groups()) {
            for (WorkRowDto row : group.rows()) {
                for (CellDto cell : row.cells()) {
                    for (MaterialLineDto line : cell.materials()) {
                        if (line.concreteMaterialId() != null) {
                            lines.add(line);
                        }
                    }
                }
            }
        }
        return lines;
    }

    /**
     * Oracle: Σ {@code MaterialLineDto.quantity} over every cell of each room that uses the material,
     * in the room order of the matrix, only for rooms that are actually consumed (mirrors the empty
     * placeholder rule, R2.3).
     */
    private static Map<Long, BigDecimal> oracleAsIsByRoom(EstimateMatrixDto matrix, Long materialId) {
        Map<Long, BigDecimal> byRoom = new LinkedHashMap<>();
        for (WorkTypeGroupDto group : matrix.groups()) {
            for (WorkRowDto row : group.rows()) {
                for (CellDto cell : row.cells()) {
                    for (MaterialLineDto line : cell.materials()) {
                        if (materialId.equals(line.concreteMaterialId())) {
                            BigDecimal qty = line.quantity() == null ? BigDecimal.ZERO : line.quantity();
                            byRoom.merge(cell.roomId(), qty, BigDecimal::add);
                        }
                    }
                }
            }
        }
        return byRoom;
    }

    /** A reserve map assigning an arbitrary-but-fixed percent to each concrete material in the matrix. */
    private static MaterialsReserveMap reserveMapFor(EstimateMatrixDto matrix) {
        Map<Long, ReserveEntry> byId = new LinkedHashMap<>();
        BigDecimal[] cycle = {new BigDecimal("5.00"), new BigDecimal("0.00"), new BigDecimal("12.50")};
        for (MaterialLineDto line : concreteLines(matrix)) {
            byId.computeIfAbsent(line.concreteMaterialId(),
                    id -> new ReserveEntry(cycle[Math.floorMod(id.intValue(), cycle.length)], null, null, null));
        }
        return byId.isEmpty() ? null : new MaterialsReserveMap(byId);
    }

    private static MaterialsReserveMap reserveMapFromPercents(Map<Long, BigDecimal> percents) {
        if (percents.isEmpty()) {
            return null;
        }
        Map<Long, ReserveEntry> byId = new LinkedHashMap<>();
        percents.forEach((id, pct) -> byId.put(id, new ReserveEntry(pct, null, null, null)));
        return new MaterialsReserveMap(byId);
    }

    // =============================================================================================
    // Generators.
    // =============================================================================================

    /** A VAT rate percentage (e.g. 0, 8, 23), non-negative, ≤2 decimals. */
    @Provide
    Arbitrary<BigDecimal> vatRates() {
        return Arbitraries.of(
                new BigDecimal("0.00"),
                new BigDecimal("8.00"),
                new BigDecimal("23.00"));
    }

    /** A non-negative quantity with up to 4 decimals (matches the resolved Physical_Quantity scale). */
    @Provide
    Arbitrary<BigDecimal> quantities() {
        return Arbitraries.longs().between(0, 5_000_000).map(v -> BigDecimal.valueOf(v, 4));
    }

    /** A reserve percent (0..100, ≤2 decimals) or {@code null} (unset ⇒ identity). */
    @Provide
    Arbitrary<BigDecimal> percentsOrNull() {
        return Arbitraries.oneOf(
                Arbitraries.just((BigDecimal) null),
                Arbitraries.longs().between(0, 10_000).map(v -> BigDecimal.valueOf(v, 2)));
    }

    /** A per-concrete-material reserve percent map over the bounded material universe. */
    @Provide
    Arbitrary<Map<Long, BigDecimal>> percentByMaterial() {
        Arbitrary<Long> ids = Arbitraries.of(CONCRETE_MATERIAL_IDS);
        Arbitrary<BigDecimal> pct = Arbitraries.longs().between(0, 10_000).map(v -> BigDecimal.valueOf(v, 2));
        return Arbitraries.maps(ids, pct).ofMinSize(0).ofMaxSize(CONCRETE_MATERIAL_IDS.length);
    }

    /** A kosztorys read model: rooms + work groups whose cells carry mixed material lines. */
    @Provide
    Arbitrary<EstimateMatrixDto> matrices() {
        Arbitrary<List<Long>> roomIds = Arbitraries.of(ROOM_IDS)
                .set().ofMinSize(0).ofMaxSize(ROOM_IDS.length)
                .map(ArrayList::new);

        return roomIds.flatMap(rooms -> {
            List<EstimateMatrixRoomDto> roomDtos = new ArrayList<>();
            for (Long id : rooms) {
                roomDtos.add(new EstimateMatrixRoomDto(id, "room-" + id, null, null));
            }
            return workGroups(rooms).map(groups -> new EstimateMatrixDto(
                    1L,
                    true,
                    roomDtos,
                    groups,
                    BranchSubtotals.ZERO,
                    new BigDecimal("42.00"), // fillIndicatorPct — passed through verbatim
                    BigDecimal.ZERO,
                    MoneyRange.ZERO,
                    null));
        });
    }

    private Arbitrary<List<WorkTypeGroupDto>> workGroups(List<Long> roomIds) {
        return workRow(roomIds).list().ofMinSize(0).ofMaxSize(3)
                .map(rows -> List.of(new WorkTypeGroupDto(
                        1L, "cat", rows, BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO)));
    }

    private Arbitrary<WorkRowDto> workRow(List<Long> roomIds) {
        return cellsForRooms(roomIds).map(cells -> new WorkRowDto(
                1L, "work", List.of(), cells, BigDecimal.ZERO, MoneyRange.ZERO));
    }

    private Arbitrary<List<CellDto>> cellsForRooms(List<Long> roomIds) {
        if (roomIds.isEmpty()) {
            return Arbitraries.just(List.of());
        }
        // One cell per room; each cell carries 0..3 material lines (concrete or Placeholder).
        List<Arbitrary<CellDto>> cellArbs = new ArrayList<>();
        for (Long roomId : roomIds) {
            cellArbs.add(materialLines().map(lines -> new CellDto(
                    1L, roomId, true, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                    BigDecimal.ZERO, lines, MoneyRange.ZERO, FillState.filled)));
        }
        return Combinators.combine(cellArbs).as(ArrayList::new);
    }

    private Arbitrary<List<MaterialLineDto>> materialLines() {
        return materialLine().list().ofMinSize(0).ofMaxSize(3);
    }

    private Arbitrary<MaterialLineDto> materialLine() {
        // ~1 in 4 lines is a Placeholder (null concrete id); the rest are concrete over the bounded
        // material universe, with a net that may be null (~1 in 4) to exercise null-net exclusion.
        Arbitrary<Long> concreteId = Arbitraries.oneOf(
                Arbitraries.just((Long) null),
                Arbitraries.of(CONCRETE_MATERIAL_IDS),
                Arbitraries.of(CONCRETE_MATERIAL_IDS),
                Arbitraries.of(CONCRETE_MATERIAL_IDS));
        Arbitrary<ConsumptionBranch> branch =
                Arbitraries.of(ConsumptionBranch.construction, ConsumptionBranch.finishing);
        Arbitrary<ConsumptionBasis> basis =
                Arbitraries.of(ConsumptionBasis.PER_UNIT, ConsumptionBasis.PER_ROOM);
        Arbitrary<BigDecimal> qty = Arbitraries.longs().between(0, 1_000_000).map(v -> BigDecimal.valueOf(v, 4));

        return Combinators.combine(concreteId, branch, basis, qty).as((id, br, ba, q) -> {
            // Concrete net is deterministic per material id so all lines of the same material agree
            // on net (the assembler captures the first line's net for the row); a fixed subset has a
            // null net to exercise R12.4.
            BigDecimal net = null;
            if (id != null) {
                net = id.equals(CONCRETE_MATERIAL_IDS[2])
                        ? null // one material has no net price (R12.4)
                        : BigDecimal.valueOf(1000L + id, 2);
            }
            return new MaterialLineDto(
                    1L, br, 7L, "type", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                    id, id == null ? null : "mat-" + id, net, "m2", q, false, ba, false);
        });
    }
}
