package com.foremen.service.estimate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
import com.foremen.service.pricing.EffectivePriceResolver;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for {@link PackagePriceSnapshotService#snapshotForLine} — snapshot
 * completeness at add-time (FOR-05-03, design §6.2).
 *
 * <p>{@code OfferPackageDao}, {@code WorkPriceDao}, and {@code EstimateLinePackagePriceHistoryDao}
 * are Spring Data repositories, so they are mocked (via Mockito, bundled with
 * {@code spring-boot-starter-test}) rather than instantiated; {@link EffectivePriceResolver} is
 * exercised as the real, pure, stateless component (per its own property test convention) since its
 * behaviour is not what this property is validating. {@code snapshotForLine} does not itself call
 * the history DAO (history rows are built in-memory by {@code captureHistory} and persisted by the
 * caller per the class Javadoc), so the mock is unstubbed and only exists to satisfy the
 * constructor.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 5: Snapshot completeness at add-time
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.6</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 5: Snapshot completeness at add-time")
class PackagePriceSnapshotServiceCompletenessPropertyTest {

    private final EffectivePriceResolver effectivePriceResolver = new EffectivePriceResolver();

    // ------------------------------------------------------------------------------------------
    // Property 5: Snapshot completeness at add-time
    // Validates: Requirements 4.1, 4.2, 4.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 5: Snapshot completeness at add-time")
    void snapshotContainsExactlyOneRowPerPackageExistingAtAddTime(
            @ForAll("offerPackageSets") List<OfferPackageEntity> packagesAtAddTime) {

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(packagesAtAddTime);
        // no WorkPrice aggregator for the work item: every package resolves unpriced, which is
        // irrelevant to completeness (R4.1/R4.2/R4.6) — only the row count/identity is asserted.
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of());

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLineEntity line = new EstimateLineEntity();
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);
        line.setWorkItem(workItem);

        List<EstimateLinePackagePriceEntity> rows = service.snapshotForLine(line);

        // R4.1/R4.2: exactly N rows for N packages existing at add-time — one per package.
        assertThat(rows).hasSize(packagesAtAddTime.size());

        Set<Long> expectedPackageIds = packagesAtAddTime.stream()
                .map(OfferPackageEntity::getId)
                .collect(Collectors.toSet());
        Set<Long> actualPackageIds = rows.stream()
                .map(row -> row.getOfferPackage().getId())
                .collect(Collectors.toSet());

        // R4.2: no duplicates — the row set's package ids equal the input package ids exactly.
        assertThat(actualPackageIds).isEqualTo(expectedPackageIds);
        assertThat(actualPackageIds).hasSize(rows.size());

        // R4.6: every row is tied to the owning line (the "at add-time" line, not some other one).
        assertThat(rows).allSatisfy(row -> assertThat(row.getLine()).isSameAs(line));
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 5: Snapshot completeness at add-time")
    void noRowIsProducedForAPackageCreatedAfterAddTime(
            @ForAll("offerPackageSets") List<OfferPackageEntity> packagesAtAddTime) {

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(offerPackageDao.findAll(any(Sort.class))).thenReturn(packagesAtAddTime);
        when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of());

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLineEntity line = new EstimateLineEntity();
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(1L);
        line.setWorkItem(workItem);

        List<EstimateLinePackagePriceEntity> rows = service.snapshotForLine(line);

        // R4.6: a package id not present at add-time (i.e. "created after") never appears in the
        // snapshot — the DAO is read exactly once, at call time, so nothing later can leak in.
        long afterAddTimeId = packagesAtAddTime.stream()
                .map(OfferPackageEntity::getId)
                .max(Long::compareTo)
                .orElse(0L) + 1;

        assertThat(rows).noneSatisfy(row ->
                assertThat(row.getOfferPackage().getId()).isEqualTo(afterAddTimeId));
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** 0..10 distinct {@link OfferPackageEntity} rows, each with a unique id, simulating the set of
     * offer packages that exist at the moment {@code snapshotForLine} is called. */
    @Provide
    Arbitrary<List<OfferPackageEntity>> offerPackageSets() {
        return Arbitraries.integers().between(0, 10)
                .map(this::buildDistinctPackages);
    }

    private List<OfferPackageEntity> buildDistinctPackages(int count) {
        List<OfferPackageEntity> packages = new ArrayList<>(count);
        Set<Long> usedIds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            long id = i + 1L;
            usedIds.add(id);
            OfferPackageEntity pkg = new OfferPackageEntity();
            pkg.setId(id);
            pkg.setCode("pkg-" + id);
            pkg.setOrderNo(i);
            pkg.setNameRU("Пакет " + id);
            pkg.setNamePL("Pakiet " + id);
            packages.add(pkg);
        }
        assertThat(usedIds).hasSize(count);
        return packages;
    }
}
