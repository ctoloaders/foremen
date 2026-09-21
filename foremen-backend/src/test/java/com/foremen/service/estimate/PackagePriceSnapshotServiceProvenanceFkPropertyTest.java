package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.List;

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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for {@link PackagePriceSnapshotService#snapshotForLine} — the
 * {@code workPackagePrice} provenance FK never drives the snapshot's stored value
 * (FOR-05-03, design §6.2, §4.4).
 *
 * <p>{@code OfferPackageDao}, {@code WorkPriceDao}, and {@code EstimateLinePackagePriceHistoryDao}
 * are Spring Data repositories, so they are mocked (via Mockito, bundled with
 * {@code spring-boot-starter-test}) rather than instantiated, following the same convention as
 * {@code PackagePriceSnapshotServiceCompletenessPropertyTest}; {@link EffectivePriceResolver} is
 * exercised as the real, pure, stateless component since its own behaviour is not what this
 * property is validating.
 *
 * <p>{@code originalUnitPrice}/{@code unitPrice} on {@link EstimateLinePackagePriceEntity} are
 * plain copied {@code BigDecimal} fields, not computed at read-time from the {@code
 * workPackagePrice} association. This test demonstrates that by building a snapshot row, then
 * mutating the referenced catalog {@link WorkPackagePriceEntity#setNetPrice} (and separately
 * clearing the FK) AFTER the snapshot is built, and asserting the already-copied snapshot values
 * are unaffected either way.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 7: Provenance FK never drives value
 *
 * <p><b>Validates: Requirements 2.4, 4.4</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 7: Provenance FK never drives value")
class PackagePriceSnapshotServiceProvenanceFkPropertyTest {

    private final EffectivePriceResolver effectivePriceResolver = new EffectivePriceResolver();

    // ------------------------------------------------------------------------------------------
    // Property 7: Provenance FK never drives value
    // Validates: Requirements 2.4, 4.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 7: Provenance FK never drives value")
    void mutatingCatalogPriceAfterSnapshotDoesNotChangeCopiedValues(
            @ForAll("catalogPrices") BigDecimal catalogNetPrice,
            @ForAll("catalogPrices") BigDecimal mutatedNetPrice) {

        OfferPackageEntity pkg = buildPackage(1L);
        WorkPackagePriceEntity catalogPrice = buildCatalogPrice(pkg, catalogNetPrice);
        WorkPriceEntity workPrice = new WorkPriceEntity();
        workPrice.setPackagePrices(List.of(catalogPrice));

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(List.of(pkg));
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of(workPrice));

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLineEntity line = new EstimateLineEntity();
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);
        line.setWorkItem(workItem);

        List<EstimateLinePackagePriceEntity> rows = service.snapshotForLine(line);
        assertThat(rows).hasSize(1);
        EstimateLinePackagePriceEntity row = rows.get(0);

        // sanity: the snapshot copied the catalog price present at snapshot time, and recorded
        // provenance (R4.3, R4.4).
        assertThat(row.getOriginalUnitPrice()).isEqualByComparingTo(catalogNetPrice);
        assertThat(row.getUnitPrice()).isEqualByComparingTo(catalogNetPrice);
        assertThat(row.getWorkPackagePrice()).isSameAs(catalogPrice);
        assertThat(row.isUnpriced()).isFalse();

        BigDecimal originalUnitPriceBefore = row.getOriginalUnitPrice();
        BigDecimal unitPriceBefore = row.getUnitPrice();

        // mutate the referenced catalog row's own price field AFTER the snapshot was built —
        // the provenance FK is still set, pointing at the very object whose price just changed.
        catalogPrice.setNetPrice(mutatedNetPrice);

        // R2.4/R4.4: the snapshot's copied values are unaffected by the post-snapshot catalog
        // mutation, even though the provenance FK still resolves to the mutated object.
        assertThat(row.getOriginalUnitPrice()).isEqualByComparingTo(originalUnitPriceBefore);
        assertThat(row.getUnitPrice()).isEqualByComparingTo(unitPriceBefore);
        assertThat(row.getOriginalUnitPrice()).isEqualByComparingTo(catalogNetPrice);
        assertThat(row.getUnitPrice()).isEqualByComparingTo(catalogNetPrice);
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 7: Provenance FK never drives value")
    void clearingProvenanceFkAfterSnapshotDoesNotChangeCopiedValues(
            @ForAll("catalogPrices") BigDecimal catalogNetPrice) {

        OfferPackageEntity pkg = buildPackage(1L);
        WorkPackagePriceEntity catalogPrice = buildCatalogPrice(pkg, catalogNetPrice);
        WorkPriceEntity workPrice = new WorkPriceEntity();
        workPrice.setPackagePrices(List.of(catalogPrice));

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(List.of(pkg));
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of(workPrice));

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLineEntity line = new EstimateLineEntity();
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);
        line.setWorkItem(workItem);

        List<EstimateLinePackagePriceEntity> rows = service.snapshotForLine(line);
        EstimateLinePackagePriceEntity row = rows.get(0);

        BigDecimal originalUnitPriceBefore = row.getOriginalUnitPrice();
        BigDecimal unitPriceBefore = row.getUnitPrice();

        // simulate the ON DELETE SET NULL behaviour of the provenance FK (R4.5): the catalog row
        // is "deleted", so the FK on the snapshot row is cleared.
        row.setWorkPackagePrice(null);

        // R2.4/R4.4: the row remains readable and correct — the stored numeric snapshot is
        // unchanged even though the provenance FK is now NULL.
        assertThat(row.getWorkPackagePrice()).isNull();
        assertThat(row.getOriginalUnitPrice()).isEqualByComparingTo(originalUnitPriceBefore);
        assertThat(row.getUnitPrice()).isEqualByComparingTo(unitPriceBefore);
        assertThat(row.getOriginalUnitPrice()).isEqualByComparingTo(catalogNetPrice);
        assertThat(row.getUnitPrice()).isEqualByComparingTo(catalogNetPrice);
        assertThat(row.isUnpriced()).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** Plausible catalog net prices, including zero, with 2-decimal scale (money convention). */
    @Provide
    Arbitrary<BigDecimal> catalogPrices() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000"))
                .ofScale(2);
    }

    private OfferPackageEntity buildPackage(long id) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setId(id);
        pkg.setCode("pkg-" + id);
        pkg.setOrderNo((int) id);
        pkg.setNameRU("Пакет " + id);
        pkg.setNamePL("Pakiet " + id);
        return pkg;
    }

    private WorkPackagePriceEntity buildCatalogPrice(OfferPackageEntity pkg, BigDecimal netPrice) {
        WorkPackagePriceEntity price = new WorkPackagePriceEntity();
        price.setId(1L);
        price.setOfferPackage(pkg);
        price.setNetPrice(netPrice);
        return price;
    }
}
