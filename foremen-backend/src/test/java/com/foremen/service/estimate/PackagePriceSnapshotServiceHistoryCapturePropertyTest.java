package com.foremen.service.estimate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import com.foremen.dao.EstimateLinePackagePriceHistoryDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.EstimateLinePackagePriceEntity;
import com.foremen.dao.model.EstimateLinePackagePriceHistoryEntity;
import com.foremen.service.pricing.EffectivePriceResolver;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for {@link PackagePriceSnapshotService#captureHistory} — price-history
 * capture (FOR-05-03, design §6.2/§6.3).
 *
 * <p>{@code OfferPackageDao}, {@code WorkPriceDao}, and {@code EstimateLinePackagePriceHistoryDao}
 * are Spring Data repositories, so they are mocked (via Mockito), following the sibling
 * {@code PackagePriceSnapshotServiceCompletenessPropertyTest} convention. Unlike that test, this one
 * DOES stub {@code historyDao.save(...)} — via an {@link ArgumentCaptor} that also echoes the
 * argument straight back, mirroring what a real JPA {@code save} does for a not-yet-persisted
 * entity (mutating and returning the same instance) — so the persisted snapshot's fields can be
 * asserted directly, and so successive calls can be told apart by capturing every invocation.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 12: Price-history capture
 *
 * <p><b>Validates: Requirements 6.1, 6.2</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 12: Price-history capture")
class PackagePriceSnapshotServiceHistoryCapturePropertyTest {

    private final EffectivePriceResolver effectivePriceResolver = new EffectivePriceResolver();

    // ------------------------------------------------------------------------------------------
    // Property 12: Price-history capture
    // Validates: Requirements 6.1, 6.2
    // ------------------------------------------------------------------------------------------

    /**
     * A single call to {@code captureHistory(row)} appends exactly one new history row whose
     * snapshot fields ({@code originalUnitPrice}, {@code discountKind}, {@code discountValue},
     * {@code unitPrice}) exactly match {@code row}'s state at call time (R6.1, R6.2).
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 12: Price-history capture")
    void captureHistoryPersistsOneRowMatchingCurrentState(
            @ForAll("priceStates") PriceState state) {

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        // echo the same instance back, mirroring JPA save() on a transient entity
        when(historyDao.save(any(EstimateLinePackagePriceHistoryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLinePackagePriceEntity row = new EstimateLinePackagePriceEntity();
        row.setId(1L);
        state.applyTo(row);

        EstimateLinePackagePriceHistoryEntity captured = service.captureHistory(row);

        verify(historyDao, times(1)).save(any(EstimateLinePackagePriceHistoryEntity.class));

        assertThat(captured.getOriginalUnitPrice()).isEqualTo(row.getOriginalUnitPrice());
        assertThat(captured.getDiscountKind()).isEqualTo(row.getDiscountKind());
        assertThat(captured.getDiscountValue()).isEqualTo(row.getDiscountValue());
        assertThat(captured.getUnitPrice()).isEqualTo(row.getUnitPrice());
        assertThat(captured.getPackagePrice()).isSameAs(row);
    }

    /**
     * Calling {@code captureHistory(row)} N times for evolving states of the same row produces N
     * distinct persisted history rows: prior rows are never updated (still show the OLD state) and
     * each new call appends a fresh row reflecting the state at ITS OWN capture time (R6.1, R6.2,
     * append-only R6.3).
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 12: Price-history capture")
    void successiveCapturesAppendDistinctRowsWithoutMutatingPriorOnes(
            @ForAll("priceStates") PriceState firstState,
            @ForAll("priceStates") PriceState secondState) {

        OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        WorkPriceDao workPriceDao = mock(WorkPriceDao.class);
        EstimateLinePackagePriceHistoryDao historyDao = mock(EstimateLinePackagePriceHistoryDao.class);
        when(historyDao.save(any(EstimateLinePackagePriceHistoryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PackagePriceSnapshotService service = new PackagePriceSnapshotService(
                offerPackageDao, workPriceDao, effectivePriceResolver, historyDao);

        EstimateLinePackagePriceEntity row = new EstimateLinePackagePriceEntity();
        row.setId(1L);
        firstState.applyTo(row);

        EstimateLinePackagePriceHistoryEntity firstCaptured = service.captureHistory(row);

        // mutate the row to a new state and capture again
        secondState.applyTo(row);
        EstimateLinePackagePriceHistoryEntity secondCaptured = service.captureHistory(row);

        // exactly two inserts — no update/delete call exists on the append-only DAO to begin with
        ArgumentCaptor<EstimateLinePackagePriceHistoryEntity> captor =
                ArgumentCaptor.forClass(EstimateLinePackagePriceHistoryEntity.class);
        verify(historyDao, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);

        // the two returned/captured history rows are distinct objects (two separate appends)
        assertThat(firstCaptured).isNotSameAs(secondCaptured);

        // the FIRST captured row still reflects the OLD state — it was never updated in place
        assertThat(firstCaptured.getOriginalUnitPrice()).isEqualTo(firstState.originalUnitPrice);
        assertThat(firstCaptured.getDiscountKind()).isEqualTo(firstState.discountKind);
        assertThat(firstCaptured.getDiscountValue()).isEqualTo(firstState.discountValue);
        assertThat(firstCaptured.getUnitPrice()).isEqualTo(firstState.unitPrice);

        // the SECOND captured row reflects the NEW state at its own capture time
        assertThat(secondCaptured.getOriginalUnitPrice()).isEqualTo(secondState.originalUnitPrice);
        assertThat(secondCaptured.getDiscountKind()).isEqualTo(secondState.discountKind);
        assertThat(secondCaptured.getDiscountValue()).isEqualTo(secondState.discountValue);
        assertThat(secondCaptured.getUnitPrice()).isEqualTo(secondState.unitPrice);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A generated snapshot of the mutable price fields on {@link EstimateLinePackagePriceEntity}. */
    private static final class PriceState {
        final BigDecimal originalUnitPrice;
        final DiscountKind discountKind;
        final BigDecimal discountValue;
        final BigDecimal unitPrice;

        PriceState(BigDecimal originalUnitPrice, DiscountKind discountKind,
                   BigDecimal discountValue, BigDecimal unitPrice) {
            this.originalUnitPrice = originalUnitPrice;
            this.discountKind = discountKind;
            this.discountValue = discountValue;
            this.unitPrice = unitPrice;
        }

        void applyTo(EstimateLinePackagePriceEntity row) {
            row.setOriginalUnitPrice(originalUnitPrice);
            row.setDiscountKind(discountKind);
            row.setDiscountValue(discountValue);
            row.setUnitPrice(unitPrice);
        }
    }

    @Provide
    Arbitrary<PriceState> priceStates() {
        Arbitrary<BigDecimal> prices = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000"))
                .ofScale(2)
                .injectNull(0.1);
        Arbitrary<DiscountKind> discountKinds = Arbitraries.of(DiscountKind.class).injectNull(0.3);
        Arbitrary<BigDecimal> discountValues = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100"))
                .ofScale(2)
                .injectNull(0.3);

        return Combinators.combine(prices, discountKinds, discountValues, prices)
                .as(PriceState::new);
    }
}
