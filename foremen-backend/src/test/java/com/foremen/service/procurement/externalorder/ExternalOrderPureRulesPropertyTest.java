package com.foremen.service.procurement.externalorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.foremen.dao.model.PositionStatus;
import com.foremen.service.procurement.planner.DeliveryCostAllocator;
import com.foremen.service.procurement.planner.PositionMoneyMath;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property tests of the FOR-11-03 external-order pure rules.
 *
 * <p><b>Validates: Requirements 11.5, 12.3, 12.4, 13.1, 17.2</b>
 */
class ExternalOrderPureRulesPropertyTest {

    private static final EnumSet<PositionStatus> STAGES =
            EnumSet.of(PositionStatus.WITH_BUYER, PositionStatus.ORDERED, PositionStatus.SUPPLIER_PAID);

    @Provide
    Arbitrary<BigDecimal> qty() {
        return Arbitraries.longs().between(1, 100_000L).map(v -> BigDecimal.valueOf(v, 3));
    }

    @Provide
    Arbitrary<List<BigDecimal>> qtyList() {
        return qty().list().ofMinSize(0).ofMaxSize(15);
    }

    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(0, 1_000_000L).map(v -> BigDecimal.valueOf(v, 2));
    }

    @Provide
    Arbitrary<List<ExternalOrderStatus>> orderStatuses() {
        return Arbitraries.of(ExternalOrderStatus.SENT_FOR_PAYMENT, ExternalOrderStatus.PAID)
                .list().ofMinSize(0).ofMaxSize(4);
    }

    /** Coverage exactness: within iff the 3-decimal sum fits, and remaining is consistent with it. */
    @Property(tries = 400)
    void coverageIsExact(@ForAll("qty") BigDecimal position, @ForAll("qty") BigDecimal other,
                         @ForAll("qty") BigDecimal line) {
        boolean within = ExternalOrderCoverageValidator.isWithin(position, other, line);
        assertThat(within).isEqualTo(other.add(line).compareTo(position) <= 0);
        if (within) {
            assertThat(ExternalOrderCoverageValidator.remaining(position, other)).isGreaterThanOrEqualTo(line);
        } else {
            assertThat(ExternalOrderCoverageValidator.remaining(position, other)).isLessThan(line);
        }
    }

    /** Resolver exhaustiveness: defined for every status, unchanged outside the three stages, converges. */
    @Property(tries = 600)
    void resolverIsTotalAndStable(@ForAll PositionStatus status, @ForAll("qty") BigDecimal quantity,
                                  @ForAll("qty") BigDecimal coverage,
                                  @ForAll("orderStatuses") List<ExternalOrderStatus> orders) {
        PositionStatus next = PositionOrderStageResolver.resolve(status, quantity, coverage, orders);
        assertThat(next).isNotNull();
        if (!STAGES.contains(status)) {
            assertThat(next).isEqualTo(status);
        } else {
            assertThat(STAGES).contains(next);
            PositionStatus again = PositionOrderStageResolver.resolve(next, quantity, coverage, orders);
            PositionStatus third = PositionOrderStageResolver.resolve(again, quantity, coverage, orders);
            assertThat(third).isEqualTo(again);
        }
    }

    /** Delivery allocation through the line purchase nets sums exactly to the delivery cost (R13.1). */
    @Property(tries = 300)
    void allocationSumsExactly(@ForAll("money") BigDecimal delivery,
                               @ForAll("qtyList") List<BigDecimal> quantities,
                               @ForAll("money") BigDecimal unit) {
        List<BigDecimal> nets = new ArrayList<>();
        for (BigDecimal q : quantities) {
            nets.add(PositionMoneyMath.purchaseNet(unit, q));
        }
        List<BigDecimal> shares = DeliveryCostAllocator.allocate(delivery, nets);
        if (nets.isEmpty()) {
            assertThat(shares).isEmpty();
        } else {
            assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(delivery);
        }
    }

    /** payable = lines gross + delivery gross, never negative. */
    @Property(tries = 300)
    void payableIsSumOfParts(@ForAll("money") BigDecimal unit, @ForAll("qtyList") List<BigDecimal> quantities,
                             @ForAll("money") BigDecimal delivery) {
        List<ExternalOrderMath.LineInput> in = new ArrayList<>();
        for (BigDecimal q : quantities) {
            in.add(new ExternalOrderMath.LineInput(unit, q, BigDecimal.valueOf(23)));
        }
        var r = ExternalOrderMath.compute(in, delivery, BigDecimal.valueOf(8));
        assertThat(r.payableGross()).isEqualByComparingTo(r.linesGross().add(r.deliveryGross()));
        assertThat(r.linesGross()).isEqualByComparingTo(r.linesNet().add(r.linesVat()));
        assertThat(r.payableGross().signum()).isGreaterThanOrEqualTo(0);
    }

    /** Ceiling: exceeds iff strictly greater, regardless of scale. */
    @Property(tries = 300)
    void ceilingMatchesCompare(@ForAll("money") BigDecimal purchase, @ForAll("money") BigDecimal sale) {
        assertThat(CeilingCheck.exceeds(purchase, sale)).isEqualTo(purchase.compareTo(sale) > 0);
        assertThat(CeilingCheck.exceeds(purchase.setScale(4), sale)).isEqualTo(purchase.compareTo(sale) > 0);
    }
}
