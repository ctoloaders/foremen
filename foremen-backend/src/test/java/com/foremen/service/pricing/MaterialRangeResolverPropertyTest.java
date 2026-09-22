package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.service.pricing.MaterialRangeResolver.BatchProvider;
import com.foremen.service.pricing.MaterialRangeResolver.BranchRanges;
import com.foremen.service.pricing.MaterialRangeResolver.ConsumptionRowInput;
import com.foremen.service.pricing.MaterialRangeResolver.MoneyRange;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link MaterialRangeResolver} (FOR-04-19) — the pure, deterministic,
 * in-memory computation of the material money price-range (вилка) per {@code (work item, offer
 * package)}, split into a construction and a finishing branch (Requirement 4).
 *
 * <p>The resolver is exercised through its {@code static} helpers ({@code typeBatchRange},
 * {@code branchRange}, {@code compute}) with NO Spring context and NO database: the analog batches
 * are supplied through an in-memory {@link BatchProvider} built from generated inputs. The tests
 * recompute the expected ranges independently (an oracle) rather than reusing the resolver's own
 * logic.
 *
 * <p>Covers two properties:
 * <ul>
 *   <li><b>Property 2</b> — a branch money range equals the sum, over the branch's DISTINCT material
 *       types, of the type-level batch {@code min}/{@code max}; explicit {@code 0} for an empty
 *       branch; {@code null}-{@code retailNet} exclusion; empty-batch {@code 0}; a single priced
 *       material collapses {@code min == max}; and mixed-unit rows still sum only money
 *       (Requirements 4.1–4.6).</li>
 *   <li><b>Property 3</b> — the computation is deterministic (Requirement 4.7).</li>
 * </ul>
 */
class MaterialRangeResolverPropertyTest {

    // A bounded universe so type ids collide frequently within a branch (exercising distinct-type
    // batching).
    private static final long[] TYPE_IDS = {1L, 2L, 3L};

    // ---------------------------------------------------------------------------------------------
    // Property 2: Branch money range equals the sum of type-level batch ranges (explicit-0,
    //             null-exclusion, no cross-unit physical sum).
    // Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 4.6
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-19-work-catalog-material-consumption, Property 2: Branch money range equals the sum of type-level batch ranges (explicit-0, null-exclusion, no cross-unit physical sum)")
    void branchRangeEqualsSumOfTypeBatchRanges(@ForAll("scenarios") Scenario scenario) {
        BatchProvider provider = scenario.provider();
        List<ConsumptionRowInput> rows = scenario.rows();

        BranchRanges ranges = MaterialRangeResolver.compute(rows, provider);

        // ---- Oracle: recompute each branch range independently from the generated inputs. ----
        MoneyRange expectedConstruction = oracleBranchRange(scenario, ConsumptionBranch.construction);
        MoneyRange expectedFinishing = oracleBranchRange(scenario, ConsumptionBranch.finishing);

        assertMoneyRangeEquals(ranges.construction(), expectedConstruction);
        assertMoneyRangeEquals(ranges.finishing(), expectedFinishing);

        // ---- Explicit 0 for an empty branch (Requirement 4.4). ----
        boolean hasConstructionRow = rows.stream()
                .anyMatch(r -> r.branch() == ConsumptionBranch.construction && r.materialTypeId() != null);
        boolean hasFinishingRow = rows.stream()
                .anyMatch(r -> r.branch() == ConsumptionBranch.finishing && r.materialTypeId() != null);
        if (!hasConstructionRow) {
            assertThat(ranges.construction().min()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ranges.construction().max()).isEqualByComparingTo(BigDecimal.ZERO);
        }
        if (!hasFinishingRow) {
            assertThat(ranges.finishing().min()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ranges.finishing().max()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        // ---- min <= max always holds (normQty and prices are non-negative). ----
        assertThat(ranges.construction().min()).isLessThanOrEqualTo(ranges.construction().max());
        assertThat(ranges.finishing().min()).isLessThanOrEqualTo(ranges.finishing().max());
    }

    // ---------------------------------------------------------------------------------------------
    // Property 2 — focused: type-level batch behaviours (empty batch -> 0, null exclusion, single
    // priced material -> min == max). Exercises typeBatchRange directly.
    // Validates: Requirements 4.2, 4.6
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-19-work-catalog-material-consumption, Property 2: Branch money range equals the sum of type-level batch ranges (explicit-0, null-exclusion, no cross-unit physical sum)")
    void typeBatchRangeExcludesNullsAndCollapsesSingleton(
            @ForAll("normQty") BigDecimal normQty,
            @ForAll("retailNetsWithNulls") List<BigDecimal> retailNets) {
        TypeBatch batch = new TypeBatch(TYPE_IDS[0], ConsumptionBranch.construction, retailNets);

        MoneyRange range = MaterialRangeResolver.typeBatchRange(normQty, batch);

        List<BigDecimal> priced = retailNets.stream().filter(v -> v != null).toList();

        if (priced.isEmpty()) {
            // Empty (or all-null) batch -> 0..0 (Requirement 4.6).
            assertThat(range.min()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(range.max()).isEqualByComparingTo(BigDecimal.ZERO);
        } else {
            BigDecimal minRetail = priced.stream().reduce(BigDecimal::min).orElseThrow();
            BigDecimal maxRetail = priced.stream().reduce(BigDecimal::max).orElseThrow();
            // Derived money is rounded UP to 2 decimals (grosz) by the resolver.
            assertThat(range.min()).isEqualByComparingTo(MaterialRangeResolver.money(normQty.multiply(minRetail)));
            assertThat(range.max()).isEqualByComparingTo(MaterialRangeResolver.money(normQty.multiply(maxRetail)));
            if (priced.size() == 1) {
                // A single priced material collapses the band (Requirement 4.2).
                assertThat(range.min()).isEqualByComparingTo(range.max());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Property 3: Range computation is deterministic.
    // Validates: Requirement 4.7
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-19-work-catalog-material-consumption, Property 3: Range computation is deterministic")
    void computationIsDeterministic(@ForAll("scenarios") Scenario scenario) {
        BatchProvider provider = scenario.provider();
        List<ConsumptionRowInput> rows = scenario.rows();

        BranchRanges first = MaterialRangeResolver.compute(rows, provider);
        BranchRanges second = MaterialRangeResolver.compute(rows, provider);

        assertMoneyRangeEquals(second.construction(), first.construction());
        assertMoneyRangeEquals(second.finishing(), first.finishing());
    }

    // ---------------------------------------------------------------------------------------------
    // Oracle — recompute the expected branch range independently of the resolver: sum over the
    // branch's DISTINCT types of (normQty of the first occurrence) × [MIN..MAX priced retailNet].
    // Money-only; physical quantities across units are never summed (Requirement 4.5), which the
    // sum-of-money oracle honours by construction.
    // ---------------------------------------------------------------------------------------------

    private static MoneyRange oracleBranchRange(Scenario scenario, ConsumptionBranch branch) {
        // Distinct material types of this branch, in first-seen order.
        Set<Long> distinctTypes = new LinkedHashSet<>();
        Map<Long, BigDecimal> normByType = new LinkedHashMap<>();
        for (ConsumptionRowInput row : scenario.rows()) {
            if (row.branch() != branch || row.materialTypeId() == null) {
                continue;
            }
            if (distinctTypes.add(row.materialTypeId())) {
                // Resolver uses the first-seen row per distinct type; mirror that here.
                normByType.put(row.materialTypeId(), row.normQty());
            }
        }
        if (distinctTypes.isEmpty()) {
            return MoneyRange.ZERO;
        }

        BigDecimal min = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        for (Long typeId : distinctTypes) {
            BigDecimal normQty = normByType.get(typeId);
            List<BigDecimal> priced = scenario.pricedRetailNets(typeId, branch);
            if (normQty == null || priced.isEmpty()) {
                continue; // contributes 0
            }
            BigDecimal minRetail = priced.stream().reduce(BigDecimal::min).orElseThrow();
            BigDecimal maxRetail = priced.stream().reduce(BigDecimal::max).orElseThrow();
            // Mirror the resolver: each per-type band edge is rounded UP to 2 decimals (grosz)
            // BEFORE the branch sum, so branch total == sum of the displayed per-type bands.
            min = min.add(MaterialRangeResolver.money(normQty.multiply(minRetail)));
            max = max.add(MaterialRangeResolver.money(normQty.multiply(maxRetail)));
        }
        return new MoneyRange(min, max);
    }

    private static void assertMoneyRangeEquals(MoneyRange actual, MoneyRange expected) {
        assertThat(actual.min()).isEqualByComparingTo(expected.min());
        assertThat(actual.max()).isEqualByComparingTo(expected.max());
    }

    // ---------------------------------------------------------------------------------------------
    // A generated scenario: a set of consumption rows (varying branch, distinct/repeated types,
    // normQty) plus a per-(type, branch) analog batch of retailNets (varying size incl. empty and
    // singleton, retailNet incl. null). The BatchProvider is backed by these batches.
    // ---------------------------------------------------------------------------------------------

    record Scenario(List<ConsumptionRowInput> rows,
                    Map<TypeBranchKey, List<BigDecimal>> batchesByTypeBranch) {

        BatchProvider provider() {
            return (materialTypeId, branch) -> {
                List<BigDecimal> retailNets =
                        batchesByTypeBranch.get(new TypeBranchKey(materialTypeId, branch));
                if (retailNets == null) {
                    return null; // no batch loaded -> resolver treats as empty (0..0)
                }
                return new TypeBatch(materialTypeId, branch, retailNets);
            };
        }

        /** The non-null retailNets for a (type, branch), as the resolver would see after exclusion. */
        List<BigDecimal> pricedRetailNets(Long typeId, ConsumptionBranch branch) {
            List<BigDecimal> retailNets = batchesByTypeBranch.get(new TypeBranchKey(typeId, branch));
            if (retailNets == null) {
                return List.of();
            }
            return retailNets.stream().filter(v -> v != null).toList();
        }
    }

    record TypeBranchKey(Long materialTypeId, ConsumptionBranch branch) {
    }

    // ---------------------------------------------------------------------------------------------
    // Generators.
    // ---------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        return rows().flatMap(rows -> {
            // Determine every (type, branch) key referenced by the rows, then generate a batch for
            // each (varying size incl. empty/singleton, retailNet incl. null). Some keys may get no
            // batch at all (provider returns null) — handled by generating over a superset of keys
            // and randomly dropping some.
            Set<TypeBranchKey> keys = new LinkedHashSet<>();
            for (ConsumptionRowInput row : rows) {
                if (row.materialTypeId() != null && row.branch() != null) {
                    keys.add(new TypeBranchKey(row.materialTypeId(), row.branch()));
                }
            }
            List<TypeBranchKey> keyList = new ArrayList<>(keys);
            return batchesFor(keyList).map(batches -> new Scenario(rows, batches));
        });
    }

    private Arbitrary<Map<TypeBranchKey, List<BigDecimal>>> batchesFor(List<TypeBranchKey> keys) {
        if (keys.isEmpty()) {
            return Arbitraries.just(new LinkedHashMap<>());
        }
        // For each key: either no entry (~1 in 5, provider returns null), or a batch list of
        // retailNets (0..4 entries, each null ~1 in 4 or a non-negative 2-decimal value).
        Arbitrary<Optional<List<BigDecimal>>> perKey = Arbitraries.oneOf(
                Arbitraries.just(Optional.<List<BigDecimal>>empty()),
                retailNetsWithNulls().map(Optional::of),
                retailNetsWithNulls().map(Optional::of),
                retailNetsWithNulls().map(Optional::of),
                retailNetsWithNulls().map(Optional::of));

        Arbitrary<List<Optional<List<BigDecimal>>>> lists =
                perKey.list().ofSize(keys.size());

        return lists.map(values -> {
            Map<TypeBranchKey, List<BigDecimal>> map = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                TypeBranchKey key = keys.get(i);
                values.get(i).ifPresent(nets -> map.put(key, nets));
            }
            return map;
        });
    }

    @Provide
    Arbitrary<List<ConsumptionRowInput>> rows() {
        return row().list().ofMinSize(0).ofMaxSize(10);
    }

    private Arbitrary<ConsumptionRowInput> row() {
        Arbitrary<ConsumptionBranch> branch =
                Arbitraries.of(ConsumptionBranch.construction, ConsumptionBranch.finishing);
        Arbitrary<Long> typeId = Arbitraries.of(TYPE_IDS[0], TYPE_IDS[1], TYPE_IDS[2]);
        return Combinators.combine(branch, typeId, normQty())
                .as(ConsumptionRowInput::new);
    }

    @Provide
    Arbitrary<BigDecimal> normQty() {
        // Non-negative material-unit-per-work-unit with 4 decimals (matches NUMERIC(12,4)).
        return Arbitraries.longs().between(0, 999_999).map(v -> BigDecimal.valueOf(v, 4));
    }

    @Provide
    Arbitrary<List<BigDecimal>> retailNetsWithNulls() {
        // A batch of 0..4 prices; each entry is null (~1 in 4) or a non-negative 2-decimal value.
        Arbitrary<BigDecimal> priceOrNull = Arbitraries.oneOf(
                Arbitraries.just((BigDecimal) null),
                Arbitraries.longs().between(0, 9_999_999).map(cents -> BigDecimal.valueOf(cents, 2)),
                Arbitraries.longs().between(0, 9_999_999).map(cents -> BigDecimal.valueOf(cents, 2)),
                Arbitraries.longs().between(0, 9_999_999).map(cents -> BigDecimal.valueOf(cents, 2)));
        return priceOrNull.list().ofMinSize(0).ofMaxSize(4);
    }
}
