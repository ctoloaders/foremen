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
import org.junit.jupiter.api.Test;

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
        // concreteMaterialId is a construction_materials.id for construction lines and a
        // finishing_materials.id for finishing lines — two independent id spaces — so the SAME
        // numeric id in BOTH branches is TWO distinct rows. The row identity is the (branch, id) pair.
        Set<MaterialRef> expectedRefs = new LinkedHashSet<>();
        // The material TYPE captured for each row is the FIRST concrete line of that (branch, id) pair
        // (fix #2): a filter attribute carried through, distinct from the concrete material identity.
        Map<MaterialRef, Long> expectedTypeIdByRef = new LinkedHashMap<>();
        Map<MaterialRef, String> expectedTypeNameByRef = new LinkedHashMap<>();
        for (MaterialLineDto line : concreteLines(matrix)) {
            MaterialRef ref = new MaterialRef(line.branch(), line.concreteMaterialId());
            expectedRefs.add(ref);
            expectedTypeIdByRef.putIfAbsent(ref, line.typeId());
            expectedTypeNameByRef.putIfAbsent(ref, line.typeName());
        }

        // Collect every emitted row as a (branch, materialId) ref and the branch group it sits in.
        List<MaterialRef> emittedRefs = new ArrayList<>();
        Set<ConsumptionBranch> seenBranchGroups = new LinkedHashSet<>();

        for (MaterialBranchGroupDto group : dto.branches()) {
            // Every branch group is a real branch and appears at most once (branch groups partition).
            assertThat(seenBranchGroups.add(group.branch()))
                    .as("branch group %s appears exactly once", group.branch())
                    .isTrue();
            for (MaterialRowDto row : group.rows()) {
                MaterialRef ref = new MaterialRef(group.branch(), row.materialId());
                emittedRefs.add(ref);
                // A row's own branch field agrees with the group it is partitioned into (R1.5).
                assertThat(row.branch())
                        .as("row %s branch matches its group", row.materialId())
                        .isEqualTo(group.branch());
                // The row carries the material TYPE from its first concrete line (fix #2): a filter
                // attribute distinct from the concrete material identity.
                assertThat(row.typeId())
                        .as("row %s carries the type id of its first concrete line", row.materialId())
                        .isEqualTo(expectedTypeIdByRef.get(ref));
                assertThat(row.typeName())
                        .as("row %s carries the type name of its first concrete line", row.materialId())
                        .isEqualTo(expectedTypeNameByRef.get(ref));
            }
        }

        // Exactly one row per distinct (branch, concrete material id) — no duplicates (R1.2), and the
        // same product repeated across cells/rooms within a branch folds into a single row.
        assertThat(emittedRefs)
                .as("no duplicate rows: exactly one row per distinct (branch, concrete material id)")
                .doesNotHaveDuplicates();

        // The emitted rows are EXACTLY the distinct (branch, concrete material id) pairs — no
        // Placeholder-only row (R1.3), none missing (R1.2).
        assertThat(new LinkedHashSet<>(emittedRefs))
                .as("rows are exactly the distinct (branch, concrete material id) pairs (Placeholders excluded)")
                .isEqualTo(expectedRefs);

        // The branch groups partition the rows: Σ group sizes == total distinct (branch, id) pairs.
        int totalRows = dto.branches().stream().mapToInt(g -> g.rows().size()).sum();
        assertThat(totalRows)
                .as("branch groups partition the rows (no row lost or double-counted)")
                .isEqualTo(expectedRefs.size());
    }

    /** A material row's composite identity: the branch plus the concrete material id (id-space fix). */
    private record MaterialRef(ConsumptionBranch branch, Long materialId) {
    }

    // =============================================================================================
    // Regression: a construction concrete material and a finishing concrete material that share the
    // same numeric id must NOT collide into one merged row (id-space fix).
    // Root cause proven against live data (project 158, room 13): construction material id 96 (tile
    // adhesive, PER_UNIT, sums to 208) and finishing material id 96 (a WC set, PER_ROOM, 1) collided
    // into a single accumulator keyed by the bare id, producing one row of 209 mislabeled as the WC
    // set. Keying by (branch, id) keeps them as two independent rows.
    // Validates: Requirements 1.2, 1.5
    // =============================================================================================

    @Test
    @Tag("Feature: for-05-05b-list-of-materials, Regression: same numeric concrete material id in both branches yields two rows")
    void sameConcreteIdInBothBranchesYieldsTwoIndependentRows() {
        long roomId = 13L;
        long sharedId = 7L; // same numeric id in two independent id spaces (construction vs finishing)

        // Construction line: tile adhesive, PER_UNIT, quantity 208.
        MaterialLineDto construction = new MaterialLineDto(
                1L, ConsumptionBranch.construction, 500L, "adhesive-type",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                sharedId, "tile adhesive", new BigDecimal("10.00"), "kg",
                new BigDecimal("208.0000"), false, ConsumptionBasis.PER_UNIT, false);

        // Finishing line: WC set, PER_ROOM, quantity 1 — same numeric id, different id space.
        MaterialLineDto finishing = new MaterialLineDto(
                2L, ConsumptionBranch.finishing, 600L, "wc-set-type",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                sharedId, "WC set", new BigDecimal("300.00"), "szt",
                new BigDecimal("1.0000"), false, ConsumptionBasis.PER_ROOM, false);

        CellDto cell = new CellDto(
                1L, roomId, true, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                BigDecimal.ZERO, List.of(construction, finishing), MoneyRange.ZERO, FillState.filled);
        WorkRowDto workRow = new WorkRowDto(1L, "work", List.of(), List.of(cell), BigDecimal.ZERO, MoneyRange.ZERO);
        WorkTypeGroupDto group = new WorkTypeGroupDto(
                1L, "cat", List.of(workRow), BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO);
        EstimateMatrixDto matrix = new EstimateMatrixDto(
                158L, true,
                List.of(new EstimateMatrixRoomDto(roomId, "room-13", null, null)),
                List.of(group),
                BranchSubtotals.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO, MoneyRange.ZERO, null);

        MaterialsListDto dto = MaterialsListAssembler.project(matrix, new BigDecimal("23.00"), null, false);

        // Exactly two rows total — not one merged row of 209 (the bug).
        int totalRows = dto.branches().stream().mapToInt(g -> g.rows().size()).sum();
        assertThat(totalRows)
                .as("two independent materials (construction id 7 + finishing id 7) ⇒ two rows, not one")
                .isEqualTo(2);

        MaterialRowDto constructionRow = rowFor(dto, ConsumptionBranch.construction, sharedId);
        MaterialRowDto finishingRow = rowFor(dto, ConsumptionBranch.finishing, sharedId);

        // The construction row carries its own quantity (208), independent of the finishing line.
        assertThat(constructionRow.materialName())
                .as("construction row keeps its own identity (tile adhesive)")
                .isEqualTo("tile adhesive");
        assertThat(constructionRow.asIsTotalQty())
                .as("construction as-is total is 208, not 209")
                .isEqualByComparingTo(new BigDecimal("208.0000"));

        // The finishing row carries its own quantity (1), NOT the merged 209.
        assertThat(finishingRow.materialName())
                .as("finishing row keeps its own identity (WC set)")
                .isEqualTo("WC set");
        assertThat(finishingRow.asIsTotalQty())
                .as("finishing as-is total is 1, not 209")
                .isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    /** Find the single emitted row for a (branch, materialId) pair; fails if absent or duplicated. */
    private static MaterialRowDto rowFor(MaterialsListDto dto, ConsumptionBranch branch, long materialId) {
        List<MaterialRowDto> matches = new ArrayList<>();
        for (MaterialBranchGroupDto group : dto.branches()) {
            if (group.branch() != branch) {
                continue;
            }
            for (MaterialRowDto row : group.rows()) {
                if (row.materialId() != null && row.materialId() == materialId) {
                    matches.add(row);
                }
            }
        }
        assertThat(matches)
                .as("exactly one %s row for material id %s", branch, materialId)
                .hasSize(1);
        return matches.get(0);
    }

    // =============================================================================================
    // Amendment: when the norm unit is the piece unit (szt / шт), the two Row_Total figures — the
    // as-is total and the effective total — are rounded UP to whole numbers (a piece cannot be
    // fractional), regardless of reserve percent and regardless of consumption basis. Per-room cells
    // stay EXACT; a non-piece unit is never rounded.
    // =============================================================================================

    @Test
    @Tag("Feature: for-05-05b-list-of-materials, Amend: szt row totals round up to whole numbers")
    void sztRowTotalsRoundUpToWholeNumbers() {
        // --- Case 1: PER_ROOM szt material across two rooms, per-line 0.7 + 0.5 = 1.2 as-is total. ---
        long roomA = 100L;
        long roomB = 101L;

        MaterialLineDto lineA = new MaterialLineDto(
                1L, ConsumptionBranch.construction, 7L, "type",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                50L, "screws", new BigDecimal("3.00"), "szt",
                new BigDecimal("0.7000"), false, ConsumptionBasis.PER_ROOM, false);
        MaterialLineDto lineB = new MaterialLineDto(
                2L, ConsumptionBranch.construction, 7L, "type",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                50L, "screws", new BigDecimal("3.00"), "szt",
                new BigDecimal("0.5000"), false, ConsumptionBasis.PER_ROOM, false);

        CellDto cellA = new CellDto(
                1L, roomA, true, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                BigDecimal.ZERO, List.of(lineA), MoneyRange.ZERO, FillState.filled);
        CellDto cellB = new CellDto(
                2L, roomB, true, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                BigDecimal.ZERO, List.of(lineB), MoneyRange.ZERO, FillState.filled);
        WorkRowDto workRow = new WorkRowDto(
                1L, "work", List.of(), List.of(cellA, cellB), BigDecimal.ZERO, MoneyRange.ZERO);
        WorkTypeGroupDto group = new WorkTypeGroupDto(
                1L, "cat", List.of(workRow), BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO);
        EstimateMatrixDto matrix = new EstimateMatrixDto(
                1L, true,
                List.of(new EstimateMatrixRoomDto(roomA, "room-a", null, null),
                        new EstimateMatrixRoomDto(roomB, "room-b", null, null)),
                List.of(group),
                BranchSubtotals.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO, MoneyRange.ZERO, null);

        MaterialsListDto dto = MaterialsListAssembler.project(matrix, new BigDecimal("23.00"), null, false);
        MaterialRowDto row = rowFor(dto, ConsumptionBranch.construction, 50L);

        // Both Row_Total figures round UP from 1.2 to the whole number 2 (PER_ROOM ⇒ no reserve, so
        // the effective raw would be 1.2, still rounded up to 2 by the piece-unit rule).
        assertThat(row.asIsTotalQty().compareTo(new BigDecimal("2")))
                .as("szt as-is Row_Total rounds up 1.2 → 2").isZero();
        assertThat(row.effectiveTotalQty().compareTo(new BigDecimal("2")))
                .as("szt effective Row_Total rounds up 1.2 → 2").isZero();

        // The per-room cells stay EXACT (0.7 and 0.5), never rounded.
        assertThat(row.cells()).hasSize(2);
        assertThat(row.cells().get(0).quantity())
                .as("room A cell stays exact 0.7").isEqualByComparingTo(new BigDecimal("0.7000"));
        assertThat(row.cells().get(1).quantity())
                .as("room B cell stays exact 0.5").isEqualByComparingTo(new BigDecimal("0.5000"));

        // --- Case 2: PER_UNIT szt material with a fractional as-is and a reserve percent. ---
        // norm×Volume already resolved into quantity=2.5; reserve 10% ⇒ raw effective ceil(2.75)=3;
        // as-is total ceil(2.5)=3.
        MaterialLineDto perUnit = new MaterialLineDto(
                3L, ConsumptionBranch.finishing, 8L, "type",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                60L, "sockets", new BigDecimal("5.00"), "szt",
                new BigDecimal("2.5000"), false, ConsumptionBasis.PER_UNIT, false);
        CellDto perUnitCell = new CellDto(
                3L, roomA, true, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                BigDecimal.ZERO, List.of(perUnit), MoneyRange.ZERO, FillState.filled);
        WorkRowDto perUnitWorkRow = new WorkRowDto(
                2L, "work", List.of(), List.of(perUnitCell), BigDecimal.ZERO, MoneyRange.ZERO);
        WorkTypeGroupDto perUnitGroup = new WorkTypeGroupDto(
                2L, "cat", List.of(perUnitWorkRow), BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO);
        EstimateMatrixDto perUnitMatrix = new EstimateMatrixDto(
                1L, true,
                List.of(new EstimateMatrixRoomDto(roomA, "room-a", null, null)),
                List.of(perUnitGroup),
                BranchSubtotals.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO, MoneyRange.ZERO, null);

        MaterialsReserveMap reserve = new MaterialsReserveMap(
                Map.of(60L, new ReserveEntry(new BigDecimal("10.00"), null, null, null)));
        MaterialsListDto perUnitDto =
                MaterialsListAssembler.project(perUnitMatrix, new BigDecimal("23.00"), reserve, false);
        MaterialRowDto perUnitRow = rowFor(perUnitDto, ConsumptionBranch.finishing, 60L);

        // as-is total = ceil(2.5) = 3; effective = ceil(2.5 × 1.10 = 2.75) = 3 (both whole).
        assertThat(perUnitRow.asIsTotalQty().compareTo(new BigDecimal("3")))
                .as("szt PER_UNIT as-is Row_Total is ceil of raw 2.5 → 3").isZero();
        assertThat(perUnitRow.effectiveTotalQty().compareTo(new BigDecimal("3")))
                .as("szt PER_UNIT effective Row_Total is whole → 3").isZero();
        // Per-room cell stays exact 2.5.
        assertThat(perUnitRow.cells().get(0).quantity())
                .as("PER_UNIT cell stays exact 2.5").isEqualByComparingTo(new BigDecimal("2.5000"));

        // --- Control: a non-piece unit (m2) with a fractional as-is total is NOT rounded. ---
        MaterialLineDto m2Line = new MaterialLineDto(
                4L, ConsumptionBranch.construction, 9L, "type",
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                70L, "tiles", new BigDecimal("4.00"), "m2",
                new BigDecimal("1.2000"), false, ConsumptionBasis.PER_ROOM, false);
        CellDto m2Cell = new CellDto(
                4L, roomA, true, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                BigDecimal.ZERO, List.of(m2Line), MoneyRange.ZERO, FillState.filled);
        WorkRowDto m2WorkRow = new WorkRowDto(
                3L, "work", List.of(), List.of(m2Cell), BigDecimal.ZERO, MoneyRange.ZERO);
        WorkTypeGroupDto m2Group = new WorkTypeGroupDto(
                3L, "cat", List.of(m2WorkRow), BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO);
        EstimateMatrixDto m2Matrix = new EstimateMatrixDto(
                1L, true,
                List.of(new EstimateMatrixRoomDto(roomA, "room-a", null, null)),
                List.of(m2Group),
                BranchSubtotals.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO, MoneyRange.ZERO, null);

        MaterialsListDto m2Dto = MaterialsListAssembler.project(m2Matrix, new BigDecimal("23.00"), null, false);
        MaterialRowDto m2Row = rowFor(m2Dto, ConsumptionBranch.construction, 70L);

        // m2 ⇒ NO rounding: as-is total stays fractional 1.2 (PER_ROOM ⇒ effective == as-is 1.2).
        assertThat(m2Row.asIsTotalQty())
                .as("m2 as-is Row_Total stays fractional 1.2 (no piece rounding)")
                .isEqualByComparingTo(new BigDecimal("1.2000"));
        assertThat(m2Row.effectiveTotalQty())
                .as("m2 effective Row_Total stays fractional 1.2 (no piece rounding)")
                .isEqualByComparingTo(new BigDecimal("1.2000"));
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
                // (branch, material id) pair (the resolved, override/basis-aware Physical_Quantity,
                // R7.1, R7.3). The pair — not the bare id — is the row identity (id-space fix). ----
                Map<Long, BigDecimal> asIsByRoom =
                        oracleAsIsByRoom(matrix, group.branch(), row.materialId());

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
    private static Map<Long, BigDecimal> oracleAsIsByRoom(
            EstimateMatrixDto matrix, ConsumptionBranch branch, Long materialId) {
        Map<Long, BigDecimal> byRoom = new LinkedHashMap<>();
        for (WorkTypeGroupDto group : matrix.groups()) {
            for (WorkRowDto row : group.rows()) {
                for (CellDto cell : row.cells()) {
                    for (MaterialLineDto line : cell.materials()) {
                        // Match on the (branch, concreteMaterialId) pair — the row identity — so a
                        // same-id line in the other branch does NOT leak into this row's quantities.
                        if (materialId.equals(line.concreteMaterialId()) && branch == line.branch()) {
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
