package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.data.domain.Sort;

import com.foremen.dao.EstimateLinePackagePriceHistoryDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLinePackagePriceEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.pricing.EffectivePriceResolver;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for {@link PackagePriceSnapshotService#snapshotForLine} — MAX-fallback copy
 * correctness (FOR-05-03, design §6.2, Property 6).
 *
 * <p>Mirrors {@code PackagePriceSnapshotServiceCompletenessPropertyTest}'s DAO-mocking convention:
 * {@code OfferPackageDao}, {@code WorkPriceDao}, and {@code EstimateLinePackagePriceHistoryDao} are
 * mocked (Spring Data repositories), while the real {@link EffectivePriceResolver} is exercised
 * directly so the assertion can independently recompute its expected value and compare it against
 * the snapshot's {@code originalUnitPrice} for exact agreement.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 6: MAX-fallback copy correctness
 *
 * <p><b>Validates: Requirements 4.3, 4.7</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 6: MAX-fallback copy correctness")
class PackagePriceSnapshotServiceMaxFallbackPropertyTest {

    private final EffectivePriceResolver effectivePriceResolver = new EffectivePriceResolver();

    // ------------------------------------------------------------------------------------------
    // Property 6: MAX-fallback copy correctness
    // Validates: Requirements 4.3, 4.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 6: MAX-fallback copy correctness")
    void originalUnitPriceMatchesEffectivePriceResolverForEveryPackage(
            @ForAll("catalogScenarios") CatalogScenario scenario) {

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(scenario.packages);

        WorkPriceEntity workPrice = new WorkPriceEntity();
        workPrice.setId(1L);
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);
        workPrice.setWorkItem(workItem);
        workPrice.setPackagePrices(scenario.workPackagePrices);
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of(workPrice));

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setWorkItem(workItem);

        List<EstimateLinePackagePriceEntity> rows = service.snapshotForLine(line);

        assertThat(rows).hasSize(scenario.packages.size());

        for (EstimateLinePackagePriceEntity row : rows) {
            OfferPackageEntity pkg = row.getOfferPackage();
            Optional<BigDecimal> expected =
                    effectivePriceResolver.resolve(scenario.workPackagePrices, pkg.getCode());

            if (expected.isPresent()) {
                // R4.3: originalUnitPrice equals exactly what the resolver returns for this package.
                assertThat(row.getOriginalUnitPrice()).isEqualByComparingTo(expected.get());
                assertThat(row.isUnpriced()).isFalse();
                // no discount yet on a fresh snapshot: effective unitPrice = originalUnitPrice (R5.2)
                assertThat(row.getUnitPrice()).isEqualByComparingTo(expected.get());
            } else {
                // R4.7: resolver empty -> unpriced=true, both prices null, never fabricated.
                assertThat(row.isUnpriced()).isTrue();
                assertThat(row.getOriginalUnitPrice()).isNull();
                assertThat(row.getUnitPrice()).isNull();
            }
        }
    }

    @Property(tries = 50)
    @Tag("Feature: FOR-05-03-estimate-core, Property 6: MAX-fallback copy correctness")
    void noMatchingWorkPackagePriceYieldsUnpricedRowWithNullPrices(
            @ForAll("offerPackages") OfferPackageEntity pkg) {

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(List.of(pkg));

        // No WorkPrice aggregator at all for the work item -> resolver always returns empty
        // (R4.7's "unpriced" branch: EffectivePriceResolver.resolve(List.of(), pkg.getCode())).
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of());

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLineEntity line = new EstimateLineEntity();
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);
        line.setWorkItem(workItem);

        List<EstimateLinePackagePriceEntity> rows = service.snapshotForLine(line);

        assertThat(rows).hasSize(1);
        EstimateLinePackagePriceEntity row = rows.get(0);
        // R4.7: no catalog price at all for the work item -> unpriced, null prices, never fabricated.
        assertThat(row.isUnpriced()).isTrue();
        assertThat(row.getOriginalUnitPrice()).isNull();
        assertThat(row.getUnitPrice()).isNull();
        assertThat(row.getWorkPackagePrice()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A single {@link OfferPackageEntity} with a fixed id/code, for the isolated unpriced case. */
    @Provide
    Arbitrary<OfferPackageEntity> offerPackages() {
        return Arbitraries.integers().between(1, 5).map(id -> buildPackage(id, "pkg-" + id));
    }

    /**
     * A scenario with 1..8 distinct {@link OfferPackageEntity} rows and 0..8 catalog
     * {@code WorkPackagePrice} rows, each priced against one of the offer packages (possibly
     * repeating packages across price rows is avoided by construction, one price per package at
     * most, so that {@code EffectivePriceResolver}'s branch-1 exact match is exercised for some
     * packages while others fall back to the branch-2 MAX, and packages with a price row missing
     * entirely only get one when the WorkPackagePrice list is non-empty (branch 2) or none at all
     * when it is empty (branch 3, R4.7).
     */
    @Provide
    Arbitrary<CatalogScenario> catalogScenarios() {
        Arbitrary<Integer> packageCount = Arbitraries.integers().between(1, 8);
        return packageCount.flatMap(pCount -> {
            List<OfferPackageEntity> packages = new ArrayList<>(pCount);
            for (int i = 0; i < pCount; i++) {
                packages.add(buildPackage(i + 1L, "pkg-" + (i + 1)));
            }

            // For each package, independently decide whether a WorkPackagePrice row exists for it,
            // and if so, its net price. This lets some packages match branch 1 exactly while the
            // rest rely on the MAX fallback (branch 2), and also covers "no rows at all" (branch 3).
            Arbitrary<List<Optional<BigDecimal>>> perPackagePrice = Arbitraries.integers()
                    .between(0, 1)
                    .list()
                    .ofSize(pCount)
                    .flatMap(presenceFlags -> {
                        List<Arbitrary<Optional<BigDecimal>>> perSlot = new ArrayList<>();
                        for (Integer flag : presenceFlags) {
                            if (flag == 1) {
                                perSlot.add(Arbitraries.bigDecimals()
                                        .between(BigDecimal.ZERO, BigDecimal.valueOf(100000))
                                        .ofScale(2)
                                        .map(Optional::of));
                            } else {
                                perSlot.add(Arbitraries.just(Optional.empty()));
                            }
                        }
                        return Combinators.combine(perSlot).as((values) -> values);
                    });

            return perPackagePrice.map(prices -> {
                List<WorkPackagePriceEntity> workPackagePrices = new ArrayList<>();
                for (int i = 0; i < pCount; i++) {
                    Optional<BigDecimal> price = prices.get(i);
                    if (price.isPresent()) {
                        workPackagePrices.add(buildWorkPackagePrice(packages.get(i), price.get()));
                    }
                }
                return new CatalogScenario(packages, workPackagePrices);
            });
        });
    }

    private OfferPackageEntity buildPackage(long id, String code) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setId(id);
        pkg.setCode(code);
        pkg.setOrderNo((int) id);
        pkg.setNameRU("Пакет " + id);
        pkg.setNamePL("Pakiet " + id);
        return pkg;
    }

    private WorkPackagePriceEntity buildWorkPackagePrice(OfferPackageEntity pkg, BigDecimal netPrice) {
        WorkPackagePriceEntity price = new WorkPackagePriceEntity();
        price.setOfferPackage(pkg);
        price.setNetPrice(netPrice);
        return price;
    }

    /** A generated catalog scenario: the offer packages at add-time and the work item's catalog
     * {@code WorkPackagePrice} rows, some matching a package exactly, some absent. */
    private static final class CatalogScenario {
        final List<OfferPackageEntity> packages;
        final List<WorkPackagePriceEntity> workPackagePrices;

        CatalogScenario(List<OfferPackageEntity> packages, List<WorkPackagePriceEntity> workPackagePrices) {
            this.packages = packages;
            this.workPackagePrices = workPackagePrices;
        }
    }
}
