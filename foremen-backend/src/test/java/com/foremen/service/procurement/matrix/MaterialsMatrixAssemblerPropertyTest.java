package com.foremen.service.procurement.matrix;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link MaterialsMatrixAssembler} (FOR-06-05-materials-matrix, design §B1,
 * §Correctness Properties). Task 1.1a — placed next to the implementation it validates; auto-folded
 * into task 6.2's run.
 *
 * <p>The assembler is a pure function from the FOR-05-05b planned rows + the FOR-06-04 reconciled
 * lines to the {@link MaterialsMatrixDto}, so every property drives
 * {@link MaterialsMatrixAssembler#project(Long, List, List)} directly with generated inputs — no
 * Spring context, no database. Each property runs a minimum of 100 iterations and is tagged with its
 * design property number.
 *
 * <ul>
 *   <li><b>Property 1 — Fact fold aggregates all linked analogs at full weight, tier-independent.</b>
 *       {@code Fact_Brutto} is the sum of {@code lineTotal} over exactly the lines with
 *       {@code matchedMaterialId == materialId} and {@code matchStatus ∈ {AUTO, MANUAL_MATCHED}}, and
 *       is invariant under re-labelling a linked line's tier. Validates Requirements 1.2, 1.3, 1.5.</li>
 *   <li><b>Property 2 — Money-based % выборки is uncapped and divide-by-zero-safe.</b> Validates
 *       Requirements 1.3, 1.4, 4.1.</li>
 *   <li><b>Property 3 — Fact quantity sums only unit-matching lines and counts the rest as
 *       mismatches.</b> Validates Requirements 2.1, 2.2, 2.3, 2.4.</li>
 *   <li><b>Property 4 — Unmatched and unplanned lines contribute to no row.</b> Validates
 *       Requirement 1.6.</li>
 *   <li><b>Property 5 — Dashboard totals sum per-material contributions per branch and project-wide,
 *       excluding null-brutto rows.</b> Validates Requirements 3.2, 3.3.</li>
 * </ul>
 *
 * <p>Feature: FOR-06-05-materials-matrix, Properties 1, 2, 3, 4, 5
 */
@Tag("Feature: FOR-06-05-materials-matrix")
class MaterialsMatrixAssemblerPropertyTest {

    private static final long PROJECT_ID = 7L;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int PERCENT_SCALE = 4;

    // =============================================================================================
    // Property 1: Fact fold aggregates all linked analogs at full weight, tier-independent.
    // Fact_Brutto = Σ lineTotal over exactly the matched lines of the material, invariant under tier.
    // Validates: Requirements 1.2, 1.3, 1.5
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-05-materials-matrix, Property 1: Fact fold aggregates all linked analogs at full weight, tier-independent")
    void factBruttoSumsAllMatchedLinesAndIsTierIndependent(@ForAll("factLines") List<MatrixFactLine> lines) {
        long materialId = 101L;
        MatrixPlannedRow planned = plannedRow(materialId, ConsumptionBranch.construction, "Cat", "kg",
                bd("10"), bd("1000"));

        MatrixRowDto row = singleRow(MaterialsMatrixAssembler.project(
                PROJECT_ID, List.of(planned), lines));

        // The expected linked set: exactly matchedMaterialId == materialId and status AUTO/MANUAL_MATCHED.
        List<MatrixFactLine> expectedLinked = new ArrayList<>();
        BigDecimal expectedBrutto = BigDecimal.ZERO;
        for (MatrixFactLine line : lines) {
            if (materialId == (line.matchedMaterialId() == null ? -1L : line.matchedMaterialId())
                    && isMatched(line.matchStatus())) {
                expectedLinked.add(line);
                if (line.lineTotal() != null) {
                    expectedBrutto = expectedBrutto.add(line.lineTotal());
                }
            }
        }

        assertThat(row.linkedLineCount()).isEqualTo(expectedLinked.size());
        assertThat(row.linkedLines()).hasSize(expectedLinked.size());
        assertThat(row.factBrutto()).isEqualByComparingTo(expectedBrutto);

        // Tier-independence: re-label every linked line's tier and assert Fact_Brutto + linked set
        // are unchanged (the tier never scales the contribution, R1.2).
        List<MatrixFactLine> relabelled = new ArrayList<>();
        for (MatrixFactLine line : lines) {
            relabelled.add(withTier(line, rotateTier(line.matchTier())));
        }
        MatrixRowDto relabelledRow = singleRow(MaterialsMatrixAssembler.project(
                PROJECT_ID, List.of(planned), relabelled));
        assertThat(relabelledRow.factBrutto()).isEqualByComparingTo(row.factBrutto());
        assertThat(relabelledRow.linkedLineCount()).isEqualTo(row.linkedLineCount());
    }

    // =============================================================================================
    // Property 2: Money-based % выборки is uncapped and divide-by-zero-safe.
    // pct = factBrutto/plannedBrutto*100 (uncapped), null when plannedBrutto null/0, 0 when no lines;
    // fill = min(pct, 100) (null when pct null); overPurchased ⟺ pct > 100.
    // Validates: Requirements 1.3, 1.4, 4.1
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-05-materials-matrix, Property 2: Money-based % выборки is uncapped and divide-by-zero-safe")
    void fulfilmentIsUncappedAndDivideByZeroSafe(
            @ForAll("matchedLineTotals") List<BigDecimal> lineTotals,
            @ForAll("plannedBruttos") BigDecimal plannedBrutto) {
        long materialId = 202L;
        MatrixPlannedRow planned = plannedRow(materialId, ConsumptionBranch.finishing, "Cat", "szt",
                bd("5"), plannedBrutto);

        List<MatrixFactLine> lines = new ArrayList<>();
        BigDecimal factBrutto = BigDecimal.ZERO;
        long lineId = 1;
        for (BigDecimal total : lineTotals) {
            lines.add(matchedLine(lineId++, materialId, MatchStatus.AUTO, MatchTier.EXACT,
                    bd("1"), "szt", total));
            factBrutto = factBrutto.add(total);
        }

        MatrixRowDto row = singleRow(MaterialsMatrixAssembler.project(
                PROJECT_ID, List.of(planned), lines));

        assertThat(row.factBrutto()).isEqualByComparingTo(factBrutto);

        if (plannedBrutto == null || plannedBrutto.signum() == 0) {
            // No meaningful denominator ⇒ pct null, fill null, never over-purchased (R1.4).
            assertThat(row.fulfilmentPercent()).isNull();
            assertThat(row.fillIndicator()).isNull();
            assertThat(row.overPurchased()).isFalse();
        } else {
            BigDecimal expectedPct = factBrutto.multiply(HUNDRED)
                    .divide(plannedBrutto, PERCENT_SCALE, RoundingMode.HALF_UP);
            assertThat(row.fulfilmentPercent()).isEqualByComparingTo(expectedPct);
            // Fill is capped at 100 but pct stays uncapped.
            assertThat(row.fillIndicator()).isEqualByComparingTo(expectedPct.min(HUNDRED));
            assertThat(row.overPurchased()).isEqualTo(expectedPct.compareTo(HUNDRED) > 0);
            if (lineTotals.isEmpty()) {
                assertThat(row.fulfilmentPercent()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    // =============================================================================================
    // Property 3: Fact quantity sums only unit-matching lines and counts the rest as mismatches.
    // Fact_Quantity = Σ quantity over lines whose unitHint matches the norm unit (case/ws-insensitive,
    // blank/null never matches); Unit_Mismatch_Count = # of linked lines that don't; flag = count>0;
    // every linked line (matching or not) still counts in Fact_Brutto.
    // Validates: Requirements 2.1, 2.2, 2.3, 2.4
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-05-materials-matrix, Property 3: Fact quantity sums only unit-matching lines and counts the rest as mismatches")
    void factQuantitySumsOnlyUnitMatchingLines(@ForAll("unitScenarioLines") List<UnitLine> unitLines) {
        long materialId = 303L;
        String normUnit = "kg";
        MatrixPlannedRow planned = plannedRow(materialId, ConsumptionBranch.construction, "Cat", normUnit,
                bd("10"), bd("1000"));

        List<MatrixFactLine> lines = new ArrayList<>();
        BigDecimal expectedQuantity = BigDecimal.ZERO;
        BigDecimal expectedBrutto = BigDecimal.ZERO;
        int expectedMismatch = 0;
        long lineId = 1;
        for (UnitLine ul : unitLines) {
            lines.add(matchedLine(lineId++, materialId, MatchStatus.MANUAL_MATCHED, MatchTier.CATEGORY,
                    ul.quantity, ul.unitHint, ul.lineTotal));
            boolean matches = unitEq(ul.unitHint, normUnit);
            if (matches) {
                if (ul.quantity != null) {
                    expectedQuantity = expectedQuantity.add(ul.quantity);
                }
            } else {
                expectedMismatch++;
            }
            if (ul.lineTotal != null) {
                expectedBrutto = expectedBrutto.add(ul.lineTotal);
            }
        }

        MatrixRowDto row = singleRow(MaterialsMatrixAssembler.project(
                PROJECT_ID, List.of(planned), lines));

        assertThat(row.factQuantity()).isEqualByComparingTo(expectedQuantity);
        assertThat(row.unitMismatchCount()).isEqualTo(expectedMismatch);
        assertThat(row.unitMismatch()).isEqualTo(expectedMismatch > 0);
        // Money is unit-independent — every linked line counts in Fact_Brutto (R2.2/R2.3).
        assertThat(row.factBrutto()).isEqualByComparingTo(expectedBrutto);
        // The per-line detail marks exactly the mismatching lines.
        long markedCount = row.linkedLines().stream().filter(MatrixLinkedLineDto::unitMismatch).count();
        assertThat(markedCount).isEqualTo(expectedMismatch);
    }

    // =============================================================================================
    // Property 4: Unmatched and unplanned lines contribute to no row.
    // A line with matchStatus UNMATCHED/MANUAL_UNMATCHED or a null matchedMaterialId is in no row's
    // linked set and contributes to no Fact_Brutto / Fact_Quantity.
    // Validates: Requirement 1.6
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-05-materials-matrix, Property 4: Unmatched and unplanned lines contribute to no row")
    void unmatchedAndUnplannedLinesContributeToNoRow(@ForAll("factLines") List<MatrixFactLine> lines) {
        long materialId = 404L;
        MatrixPlannedRow planned = plannedRow(materialId, ConsumptionBranch.construction, "Cat", "kg",
                bd("10"), bd("1000"));

        // Keep only the non-contributing lines: unmatched status OR a null material id.
        List<MatrixFactLine> nonContributing = new ArrayList<>();
        for (MatrixFactLine line : lines) {
            if (!isMatched(line.matchStatus()) || line.matchedMaterialId() == null) {
                nonContributing.add(line);
            }
        }

        MatrixRowDto row = singleRow(MaterialsMatrixAssembler.project(
                PROJECT_ID, List.of(planned), nonContributing));

        assertThat(row.linkedLineCount()).isZero();
        assertThat(row.linkedLines()).isEmpty();
        assertThat(row.factBrutto()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.factQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.unitMismatchCount()).isZero();
        assertThat(row.unitMismatch()).isFalse();
        // Planned brutto is 1000, so an empty fact set means exactly 0 % (R1.4).
        assertThat(row.fulfilmentPercent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =============================================================================================
    // Property 5: Dashboard totals sum per-material contributions per branch and project-wide,
    // excluding null-brutto rows; aggregate pct = Σfact/Σplanned*100 (uncapped, null when denom 0);
    // grand total = sum of per-branch totals.
    // Validates: Requirements 3.2, 3.3
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-05-materials-matrix, Property 5: Dashboard totals sum per-material contributions per branch and project-wide, excluding null-brutto rows")
    void dashboardTotalsSumNonNullBruttoRowsPerBranchAndGrand(
            @ForAll("plannedScenarioRows") List<PlannedScenario> scenarios) {
        List<MatrixPlannedRow> plannedRows = new ArrayList<>();
        List<MatrixFactLine> lines = new ArrayList<>();
        long materialId = 1;
        long lineId = 1;

        // Expected per-branch sums over non-null-brutto rows.
        Map<ConsumptionBranch, BigDecimal> expectedPlanned = new EnumMap<>(ConsumptionBranch.class);
        Map<ConsumptionBranch, BigDecimal> expectedFact = new EnumMap<>(ConsumptionBranch.class);

        for (PlannedScenario scenario : scenarios) {
            long id = materialId++;
            plannedRows.add(plannedRow(id, scenario.branch, "Cat", "kg", bd("1"), scenario.plannedBrutto));
            BigDecimal rowFact = BigDecimal.ZERO;
            for (BigDecimal total : scenario.lineTotals) {
                lines.add(matchedLine(lineId++, id, MatchStatus.AUTO, MatchTier.EXACT,
                        bd("1"), "kg", total));
                rowFact = rowFact.add(total);
            }
            if (scenario.plannedBrutto != null) { // null-brutto rows excluded from money totals (R3.3)
                expectedPlanned.merge(scenario.branch, scenario.plannedBrutto, BigDecimal::add);
                expectedFact.merge(scenario.branch, rowFact, BigDecimal::add);
            }
        }

        MaterialsMatrixDto dto = MaterialsMatrixAssembler.project(PROJECT_ID, plannedRows, lines);

        BigDecimal grandPlanned = null;
        BigDecimal grandFact = null;
        for (MatrixBranchGroupDto branch : dto.branches()) {
            BigDecimal expP = expectedPlanned.get(branch.branch());
            BigDecimal expF = expectedFact.get(branch.branch());
            assertEqualNullable(branch.plannedBrutto(), expP);
            assertEqualNullable(branch.factBrutto(), expF);
            assertEqualNullable(branch.aggregatePercent(), expectedPct(expF, expP));
            grandPlanned = sumNullable(grandPlanned, branch.plannedBrutto());
            grandFact = sumNullable(grandFact, branch.factBrutto());
        }

        // Grand total equals the sum of the per-branch totals (R3.2).
        assertEqualNullable(dto.grandPlannedBrutto(), grandPlanned);
        assertEqualNullable(dto.grandFactBrutto(), grandFact);
        assertEqualNullable(dto.grandAggregatePercent(), expectedPct(grandFact, grandPlanned));
    }

    // =============================================================================================
    // Helpers mirroring the assembler's canonical rules (independent re-derivation for the oracle).
    // =============================================================================================

    private static boolean unitEq(String unitHint, String normUnit) {
        if (unitHint == null || unitHint.isBlank() || normUnit == null) {
            return false;
        }
        return unitHint.strip().equalsIgnoreCase(normUnit.strip());
    }

    private static boolean isMatched(MatchStatus status) {
        return status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
    }

    private static BigDecimal expectedPct(BigDecimal fact, BigDecimal planned) {
        if (planned == null || planned.signum() == 0) {
            return null;
        }
        BigDecimal f = fact == null ? BigDecimal.ZERO : fact;
        return f.multiply(HUNDRED).divide(planned, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumNullable(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.add(b);
    }

    private static void assertEqualNullable(BigDecimal actual, BigDecimal expected) {
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isEqualByComparingTo(expected);
        }
    }

    private static MatrixRowDto singleRow(MaterialsMatrixDto dto) {
        assertThat(dto.branches()).hasSize(1);
        MatrixBranchGroupDto branch = dto.branches().get(0);
        assertThat(branch.categories()).hasSize(1);
        List<MatrixRowDto> rows = branch.categories().get(0).rows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static MatrixPlannedRow plannedRow(Long materialId, ConsumptionBranch branch, String category,
                                               String unit, BigDecimal plannedQty, BigDecimal plannedBrutto) {
        return new MatrixPlannedRow(materialId, "Material-" + materialId, branch, category, unit,
                plannedQty, plannedBrutto);
    }

    private static MatrixFactLine matchedLine(long lineId, Long materialId, MatchStatus status,
                                              MatchTier tier, BigDecimal quantity, String unitHint,
                                              BigDecimal lineTotal) {
        return new MatrixFactLine(lineId, materialId, status, tier, quantity, unitHint, lineTotal,
                900L, "Supplier", null, "raw-" + lineId, "Producer");
    }

    private static MatrixFactLine withTier(MatrixFactLine line, MatchTier tier) {
        return new MatrixFactLine(line.lineId(), line.matchedMaterialId(), line.matchStatus(), tier,
                line.quantity(), line.unitHint(), line.lineTotal(), line.documentId(),
                line.supplierName(), line.uploadedAt(), line.rawName(), line.manufacturerHint());
    }

    /** Rotate a linked line's tier among the three non-NONE tiers (keeps NONE as-is for unmatched). */
    private static MatchTier rotateTier(MatchTier tier) {
        return switch (tier) {
            case EXACT -> MatchTier.MANUFACTURER_CATEGORY;
            case MANUFACTURER_CATEGORY -> MatchTier.CATEGORY;
            case CATEGORY -> MatchTier.EXACT;
            case NONE -> MatchTier.NONE;
        };
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // =============================================================================================
    // Generators
    // =============================================================================================

    /** A free multiset of reconciled lines: all tiers, all match states, matched & unmatched. */
    @Provide
    Arbitrary<List<MatrixFactLine>> factLines() {
        Arbitrary<MatchStatus> status = Arbitraries.of(MatchStatus.values());
        Arbitrary<MatchTier> tier = Arbitraries.of(MatchTier.values());
        // Material id: 101 is the row under test; -1 encodes a null material id below.
        Arbitrary<Long> materialId = Arbitraries.of(101L, 404L, 999L, -1L);
        Arbitrary<BigDecimal> lineTotal = money();
        Arbitrary<BigDecimal> quantity = money();
        Arbitrary<String> unit = Arbitraries.of("kg", "KG", " kg ", "szt", "", "  ");

        Arbitrary<MatrixFactLine> line = Combinators
                .combine(materialId, status, tier, quantity, unit, lineTotal)
                .as((mid, st, ti, qty, un, lt) -> new MatrixFactLine(
                        Arbitraries.longs().between(1, 10_000).sample(),
                        mid < 0 ? null : mid, st, ti, qty, un, lt,
                        900L, "Supplier", null, "raw", "Producer"));
        return line.list().ofMinSize(0).ofMaxSize(12);
    }

    /** Money totals for matched lines (all AUTO/EXACT in Property 2), 0..6 lines. */
    @Provide
    Arbitrary<List<BigDecimal>> matchedLineTotals() {
        return money().list().ofMinSize(0).ofMaxSize(6);
    }

    /** Planned brutto incl. null and 0 (no denominator) and over-purchase-inducing small values. */
    @Provide
    Arbitrary<BigDecimal> plannedBruttos() {
        return Arbitraries.oneOf(
                Arbitraries.just((BigDecimal) null),
                Arbitraries.just(BigDecimal.ZERO),
                Arbitraries.bigDecimals().between(bd("1"), bd("5000")).ofScale(2));
    }

    record UnitLine(BigDecimal quantity, String unitHint, BigDecimal lineTotal) {
    }

    /** Linked lines with varied units so the unit-match split is exercised (norm unit is "kg"). */
    @Provide
    Arbitrary<List<UnitLine>> unitScenarioLines() {
        Arbitrary<String> unit = Arbitraries.of("kg", "KG", " kg ", "Kg", "szt", "m2", "", "  ", null);
        Arbitrary<UnitLine> line = Combinators.combine(money(), unit, money()).as(UnitLine::new);
        return line.list().ofMinSize(0).ofMaxSize(10);
    }

    record PlannedScenario(ConsumptionBranch branch, BigDecimal plannedBrutto, List<BigDecimal> lineTotals) {
    }

    /** A set of planned rows across both branches, some with a null planned brutto. */
    @Provide
    Arbitrary<List<PlannedScenario>> plannedScenarioRows() {
        Arbitrary<ConsumptionBranch> branch = Arbitraries.of(ConsumptionBranch.values());
        Arbitrary<BigDecimal> plannedBrutto = Arbitraries.oneOf(
                Arbitraries.just((BigDecimal) null),
                Arbitraries.bigDecimals().between(bd("1"), bd("5000")).ofScale(2));
        Arbitrary<List<BigDecimal>> lineTotals = money().list().ofMinSize(0).ofMaxSize(4);
        Arbitrary<PlannedScenario> scenario = Combinators
                .combine(branch, plannedBrutto, lineTotals).as(PlannedScenario::new);
        return scenario.list().ofMinSize(1).ofMaxSize(8);
    }

    /** Non-null, non-negative money with 2-dp scale. */
    private Arbitrary<BigDecimal> money() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, bd("2000")).ofScale(2);
    }
}
