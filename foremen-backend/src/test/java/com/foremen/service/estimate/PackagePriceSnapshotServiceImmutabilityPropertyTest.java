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
 * Property-based test for {@link PackagePriceSnapshotService#snapshotForLine} — snapshot
 * immutability under catalog change (FOR-05-03, design §6.2).
 *
 * <p>{@code OfferPackageDao}, {@code WorkPriceDao}, and {@code EstimateLinePackagePriceHistoryDao}
 * are Spring Data repositories, so they are mocked (via Mockito, bundled with
 * {@code spring-boot-starter-test}) rather than instantiated, following the mocking convention of
 * {@code PackagePriceSnapshotServiceCompletenessPropertyTest}; {@link EffectivePriceResolver} is
 * exercised as the real, pure, stateless component since its own resolution behaviour is not what
 * this property is validating.
 *
 * <p>The catalog "change" is simulated by re-stubbing {@code WorkPriceDao.findByWorkItemIdIn} to
 * return a different {@code netPrice} between two calls to {@code snapshotForLine}: since
 * {@code snapshotForLine} reads the DAO fresh on every call and returns brand-new, independent
 * {@code EstimateLinePackagePriceEntity} objects (never mutating a previously-returned row), the
 * rows captured from the FIRST call are plain Java objects that are never touched again — they
 * cannot reflect a later catalog change because nothing ever writes back into them.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 4: Snapshot immutability under catalog change
 *
 * <p><b>Validates: Requirements 2.5, 4.5</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 4: Snapshot immutability under catalog change")
class PackagePriceSnapshotServiceImmutabilityPropertyTest {

    private final EffectivePriceResolver effectivePriceResolver = new EffectivePriceResolver();

    // ------------------------------------------------------------------------------------------
    // Property 4: Snapshot immutability under catalog change
    // Validates: Requirements 2.5, 4.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 4: Snapshot immutability under catalog change")
    void earlierLineSnapshotIsUnaffectedByALaterCatalogPriceChange(
            @ForAll("distinctPrices") BigDecimal oldPrice,
            @ForAll("distinctPrices") BigDecimal newPrice) {
        // only interested in genuinely different catalog prices — otherwise the property is
        // trivially true and proves nothing about immutability.
        if (oldPrice.compareTo(newPrice) == 0) {
            return;
        }

        OfferPackageEntity pkg = buildPackage(1L, "pkg-1");

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(List.of(pkg));

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);

        // (1) line A is snapshotted while the catalog price is `oldPrice`.
        when(workPriceDao.findByWorkItemIdIn(any()))
                .thenReturn(List.of(workPriceWithPackagePrice(pkg, oldPrice)));

        EstimateLineEntity lineA = new EstimateLineEntity();
        lineA.setWorkItem(workItem);
        List<EstimateLinePackagePriceEntity> lineARows = service.snapshotForLine(lineA);

        assertThat(lineARows).hasSize(1);
        BigDecimal lineAOriginalPrice = lineARows.get(0).getOriginalUnitPrice();
        BigDecimal lineAEffectivePrice = lineARows.get(0).getUnitPrice();
        assertThat(lineAOriginalPrice).isEqualByComparingTo(oldPrice);
        assertThat(lineAEffectivePrice).isEqualByComparingTo(oldPrice);

        // (2) the catalog price changes — simulated by re-stubbing the DAO to return `newPrice`.
        when(workPriceDao.findByWorkItemIdIn(any()))
                .thenReturn(List.of(workPriceWithPackagePrice(pkg, newPrice)));

        // (3) a NEW line B is snapshotted AFTER the catalog change.
        EstimateLineEntity lineB = new EstimateLineEntity();
        lineB.setWorkItem(workItem);
        List<EstimateLinePackagePriceEntity> lineBRows = service.snapshotForLine(lineB);

        // line B reflects the new catalog price (sanity check that the mock swap actually worked).
        assertThat(lineBRows).hasSize(1);
        assertThat(lineBRows.get(0).getOriginalUnitPrice()).isEqualByComparingTo(newPrice);
        assertThat(lineBRows.get(0).getUnitPrice()).isEqualByComparingTo(newPrice);

        // (4) R2.5/R4.5: line A's ALREADY-CAPTURED row objects still hold the OLD price — they were
        // never re-read or mutated by the later catalog change or by snapshotting line B.
        assertThat(lineARows.get(0).getOriginalUnitPrice()).isEqualByComparingTo(oldPrice);
        assertThat(lineARows.get(0).getUnitPrice()).isEqualByComparingTo(oldPrice);
        assertThat(lineAOriginalPrice).isEqualByComparingTo(lineARows.get(0).getOriginalUnitPrice());
        assertThat(lineAEffectivePrice).isEqualByComparingTo(lineARows.get(0).getUnitPrice());

        // line A and line B rows are distinct objects tied to their own lines — no aliasing.
        assertThat(lineARows.get(0)).isNotSameAs(lineBRows.get(0));
        assertThat(lineARows.get(0).getLine()).isSameAs(lineA);
        assertThat(lineBRows.get(0).getLine()).isSameAs(lineB);
    }

    @Property(tries = 50)
    @Tag("Feature: FOR-05-03-estimate-core, Property 4: Snapshot immutability under catalog change")
    void earlierLineSnapshotIsUnaffectedWhenTheCatalogPackagePriceIsRemovedEntirely(
            @ForAll("distinctPrices") BigDecimal oldPrice) {
        OfferPackageEntity pkg = buildPackage(1L, "pkg-1");

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(List.of(pkg));

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);

        // line A snapshotted while priced.
        when(workPriceDao.findByWorkItemIdIn(any()))
                .thenReturn(List.of(workPriceWithPackagePrice(pkg, oldPrice)));

        EstimateLineEntity lineA = new EstimateLineEntity();
        lineA.setWorkItem(workItem);
        List<EstimateLinePackagePriceEntity> lineARows = service.snapshotForLine(lineA);
        assertThat(lineARows.get(0).getOriginalUnitPrice()).isEqualByComparingTo(oldPrice);

        // catalog price row is deleted entirely (work item becomes unpriced for this package).
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of());

        EstimateLineEntity lineB = new EstimateLineEntity();
        lineB.setWorkItem(workItem);
        List<EstimateLinePackagePriceEntity> lineBRows = service.snapshotForLine(lineB);

        // line B, snapshotted after removal, is unpriced.
        assertThat(lineBRows.get(0).isUnpriced()).isTrue();
        assertThat(lineBRows.get(0).getOriginalUnitPrice()).isNull();

        // R2.5/R4.5: line A's already-captured row is untouched by the catalog row's removal.
        assertThat(lineARows.get(0).getOriginalUnitPrice()).isEqualByComparingTo(oldPrice);
        assertThat(lineARows.get(0).getUnitPrice()).isEqualByComparingTo(oldPrice);
        assertThat(lineARows.get(0).isUnpriced()).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures / generators
    // ------------------------------------------------------------------------------------------

    private OfferPackageEntity buildPackage(long id, String code) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setId(id);
        pkg.setCode(code);
        pkg.setOrderNo((int) id);
        pkg.setNameRU("Пакет " + id);
        pkg.setNamePL("Pakiet " + id);
        return pkg;
    }

    private WorkPriceEntity workPriceWithPackagePrice(OfferPackageEntity pkg, BigDecimal netPrice) {
        WorkPackagePriceEntity packagePrice = new WorkPackagePriceEntity();
        packagePrice.setOfferPackage(pkg);
        packagePrice.setNetPrice(netPrice);

        WorkPriceEntity workPrice = new WorkPriceEntity();
        workPrice.setPackagePrices(List.of(packagePrice));
        return workPrice;
    }

    /** Distinct positive prices with two decimal places, wide enough apart to never collide. */
    @Provide
    Arbitrary<BigDecimal> distinctPrices() {
        return Arbitraries.integers().between(1, 100_000)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }
}
