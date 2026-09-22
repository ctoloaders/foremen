package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.pricing.MaterialBatchLookup.BatchKey;
import com.foremen.service.pricing.MaterialRangeResolver.BranchRanges;
import com.foremen.service.pricing.MaterialRangeResolver.ConsumptionRowInput;
import com.foremen.service.pricing.MaterialRangeResolver.MoneyRange;
import com.foremen.service.pricing.WorkCatalogAggregationResolver.WorkCostCell;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property/unit tests for {@link WorkCatalogAggregationResolver#resolve(java.util.Collection)} —
 * the "no fabricated fallback" contract of FOR-05-04 Requirement 7.4/7.5: a {@link WorkCostCell}'s
 * {@code construction}/{@code finishing} ranges are the explicit {@link MoneyRange#ZERO}
 * ({@code 0..0}) whenever that branch has no consumption rows for the work item — never any other
 * value (in particular, never a percentage-of-something placeholder) — and {@code labourPrice} is
 * {@code null} whenever no {@link WorkPriceEntity} exists for the work item — never a substituted
 * default such as {@link BigDecimal#ZERO}.
 *
 * <p>Exercises the REAL integration point: {@code resolve(...)} is invoked on a real
 * {@link WorkCatalogAggregationResolver} instance constructed with mocked
 * {@link WorkMaterialConsumptionDao}, {@link WorkPriceDao}, and {@link MaterialBatchLookup}, rather
 * than reimplementing its null-handling logic in the test.
 *
 * <p>Covers Property 11 (design.md § Correctness Properties): for any work item, its total estimate
 * cost equals the single work price + norm-based construction cost + norm-based finishing cost; a
 * branch with no norm coverage contributes an explicit unpriced marker (0..0), never a fabricated
 * placeholder.
 */
class WorkCostCellNoFabricatedFallbackPropertyTest {

    private static final Long WORK_ITEM_ID = 1L;
    private static final Long CONSTRUCTION_TYPE_ID = 10L;
    private static final Long FINISHING_TYPE_ID = 20L;

    // ---------------------------------------------------------------------------------------------
    // Property 11: no fabricated fallback — zero-row branch ⇒ explicit ZERO; absent price ⇒ null.
    // Validates: Requirements 7.4, 7.5
    // ---------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 11: Three-part work cost, no fabricated fallback")
    void noFabricatedFallbackAcrossPresenceCombinations(@ForAll("scenarios") Scenario scenario) {
        WorkMaterialConsumptionDao consumptionDao = mock(WorkMaterialConsumptionDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        MaterialBatchLookup materialBatchLookup = mock(MaterialBatchLookup.class);

        when(consumptionDao.findByWorkItemIdIn(any())).thenReturn(scenario.consumptionRows());
        when(workPriceDao.findByWorkItemIdIn(any()))
                .thenReturn(scenario.hasPrice() ? List.of(scenario.priceEntity()) : List.of());
        when(materialBatchLookup.load(any())).thenReturn(Map.of()); // no analog batches loaded

        WorkCatalogAggregationResolver resolver =
                new WorkCatalogAggregationResolver(consumptionDao, workPriceDao, materialBatchLookup);

        Map<Long, WorkCostCell> result = resolver.resolve(List.of(WORK_ITEM_ID));

        assertThat(result).containsKey(WORK_ITEM_ID);
        WorkCostCell cell = result.get(WORK_ITEM_ID);

        // ---- labourPrice: null when unpriced, never a substituted default (Requirement 7.5). ----
        if (scenario.hasPrice()) {
            assertThat(cell.labourPrice()).isNotNull();
            assertThat(cell.labourPrice()).isEqualByComparingTo(scenario.netPrice());
        } else {
            assertThat(cell.labourPrice()).isNull();
        }

        // ---- construction/finishing: explicit ZERO when the branch has no rows for the work item,
        //      never any other value (no batches were loaded, so a covered branch also collapses to
        //      the resolver's own no-coverage 0..0 — this cross-checks against MaterialRangeResolver
        //      independently, per the "not a fresh computation" requirement). ----
        BranchRanges expected = MaterialRangeResolver.compute(
                toConsumptionRowInputs(scenario.consumptionRows()), MaterialRangeResolver.providerOf(Map.of()));

        assertMoneyRangeEquals(cell.construction(), expected.construction());
        assertMoneyRangeEquals(cell.finishing(), expected.finishing());

        if (!scenario.hasConstructionRow()) {
            assertMoneyRangeEquals(cell.construction(), MoneyRange.ZERO);
        }
        if (!scenario.hasFinishingRow()) {
            assertMoneyRangeEquals(cell.finishing(), MoneyRange.ZERO);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Focused unit tests — the two boundary combinations spelled out explicitly.
    // Validates: Requirements 7.4, 7.5
    // ---------------------------------------------------------------------------------------------

    @net.jqwik.api.Example
    void noConsumptionAndNoPriceYieldsFullyUnpricedCell() {
        WorkMaterialConsumptionDao consumptionDao = mock(WorkMaterialConsumptionDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        MaterialBatchLookup materialBatchLookup = mock(MaterialBatchLookup.class);

        when(consumptionDao.findByWorkItemIdIn(any())).thenReturn(List.of());
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of());
        when(materialBatchLookup.load(any())).thenReturn(Map.of());

        WorkCatalogAggregationResolver resolver =
                new WorkCatalogAggregationResolver(consumptionDao, workPriceDao, materialBatchLookup);

        WorkCostCell cell = resolver.resolve(List.of(WORK_ITEM_ID)).get(WORK_ITEM_ID);

        assertThat(cell.labourPrice()).isNull(); // unpriced, never BigDecimal.ZERO
        assertMoneyRangeEquals(cell.construction(), MoneyRange.ZERO);
        assertMoneyRangeEquals(cell.finishing(), MoneyRange.ZERO);
    }

    @net.jqwik.api.Example
    void pricedWorkWithConsumptionOnOneBranchLeavesOtherBranchExplicitZero() {
        WorkMaterialConsumptionDao consumptionDao = mock(WorkMaterialConsumptionDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        MaterialBatchLookup materialBatchLookup = mock(MaterialBatchLookup.class);

        WorkMaterialConsumptionEntity constructionRow =
                consumptionRow(ConsumptionBranch.construction, CONSTRUCTION_TYPE_ID, new BigDecimal("2.0000"));
        when(consumptionDao.findByWorkItemIdIn(any())).thenReturn(List.of(constructionRow));
        WorkPriceEntity priceEntity = priceEntity(new BigDecimal("150.00"));
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of(priceEntity));
        when(materialBatchLookup.load(any())).thenReturn(Map.of()); // no priced analog materials

        WorkCatalogAggregationResolver resolver =
                new WorkCatalogAggregationResolver(consumptionDao, workPriceDao, materialBatchLookup);

        WorkCostCell cell = resolver.resolve(List.of(WORK_ITEM_ID)).get(WORK_ITEM_ID);

        assertThat(cell.labourPrice()).isEqualByComparingTo(new BigDecimal("150.00"));
        // Construction has a row but no priced batch -> still explicit ZERO, not fabricated.
        assertMoneyRangeEquals(cell.construction(), MoneyRange.ZERO);
        // Finishing has no row at all for this work item -> explicit ZERO.
        assertMoneyRangeEquals(cell.finishing(), MoneyRange.ZERO);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture builders.
    // ---------------------------------------------------------------------------------------------

    private static WorkItemEntity workItem(Long id) {
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(id);
        return workItem;
    }

    private static WorkPriceEntity priceEntity(BigDecimal netPrice) {
        WorkPriceEntity entity = new WorkPriceEntity();
        entity.setWorkItem(workItem(WORK_ITEM_ID));
        entity.setCurrency(new CurrencyEntity());
        entity.setNetPrice(netPrice);
        return entity;
    }

    private static WorkMaterialConsumptionEntity consumptionRow(ConsumptionBranch branch, Long typeId,
                                                                 BigDecimal normQty) {
        WorkMaterialConsumptionEntity row = new WorkMaterialConsumptionEntity();
        row.setWorkItem(workItem(WORK_ITEM_ID));
        row.setBranch(branch);
        row.setNormQty(normQty);
        if (branch == ConsumptionBranch.construction) {
            ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
            type.setId(typeId);
            row.setConstructionMaterialType(type);
        } else if (branch == ConsumptionBranch.finishing) {
            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setId(typeId);
            row.setFinishingMaterialType(type);
        }
        return row;
    }

    private static List<ConsumptionRowInput> toConsumptionRowInputs(List<WorkMaterialConsumptionEntity> rows) {
        List<ConsumptionRowInput> inputs = new ArrayList<>();
        for (WorkMaterialConsumptionEntity row : rows) {
            Long typeId = row.getBranch() == ConsumptionBranch.construction
                    ? (row.getConstructionMaterialType() == null ? null : row.getConstructionMaterialType().getId())
                    : (row.getFinishingMaterialType() == null ? null : row.getFinishingMaterialType().getId());
            if (typeId == null) {
                continue;
            }
            inputs.add(new ConsumptionRowInput(row.getBranch(), typeId, row.getNormQty()));
        }
        return inputs;
    }

    private static void assertMoneyRangeEquals(MoneyRange actual, MoneyRange expected) {
        assertThat(actual.min()).isEqualByComparingTo(expected.min());
        assertThat(actual.max()).isEqualByComparingTo(expected.max());
    }

    // ---------------------------------------------------------------------------------------------
    // Generators.
    // ---------------------------------------------------------------------------------------------

    record Scenario(boolean hasPrice, BigDecimal netPrice, boolean hasConstructionRow,
                    boolean hasFinishingRow, List<WorkMaterialConsumptionEntity> consumptionRows) {

        WorkPriceEntity priceEntity() {
            return WorkCostCellNoFabricatedFallbackPropertyTest.priceEntity(netPrice);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Boolean> hasPrice = Arbitraries.of(true, false);
        Arbitrary<BigDecimal> netPrice = Arbitraries.longs().between(0, 999_999).map(v -> BigDecimal.valueOf(v, 2));
        Arbitrary<Boolean> hasConstructionRow = Arbitraries.of(true, false);
        Arbitrary<Boolean> hasFinishingRow = Arbitraries.of(true, false);
        Arbitrary<BigDecimal> normQty = Arbitraries.longs().between(0, 999_999).map(v -> BigDecimal.valueOf(v, 4));

        return Combinators.combine(hasPrice, netPrice, hasConstructionRow, hasFinishingRow, normQty)
                .as((priceFlag, price, constructionFlag, finishingFlag, qty) -> {
                    List<WorkMaterialConsumptionEntity> rows = new ArrayList<>();
                    if (constructionFlag) {
                        rows.add(consumptionRow(ConsumptionBranch.construction, CONSTRUCTION_TYPE_ID, qty));
                    }
                    if (finishingFlag) {
                        rows.add(consumptionRow(ConsumptionBranch.finishing, FINISHING_TYPE_ID, qty));
                    }
                    return new Scenario(priceFlag, price, constructionFlag, finishingFlag, rows);
                });
    }
}
