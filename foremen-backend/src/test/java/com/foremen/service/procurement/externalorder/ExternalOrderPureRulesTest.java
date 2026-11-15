package com.foremen.service.procurement.externalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.PositionStatus;
import com.foremen.exception.ForemenApiException;

/**
 * Unit tests of the FOR-11-03 external-order pure rules.
 *
 * <p><b>Validates: Requirements 11.4, 11.5, 12.3, 12.4, 16.1, 16.2, 17.2</b>
 */
class ExternalOrderPureRulesTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    // ---- status machine ----

    @Test
    void statusMachineAllowsOnlyDefinedEdges() {
        var d = ExternalOrderStatus.DRAFT;
        var s = ExternalOrderStatus.SENT_FOR_PAYMENT;
        var p = ExternalOrderStatus.PAID;
        var c = ExternalOrderStatus.CANCELLED;
        assertThat(ExternalOrderStatusMachine.canTransition(d, s)).isTrue();
        assertThat(ExternalOrderStatusMachine.canTransition(d, c)).isTrue();
        assertThat(ExternalOrderStatusMachine.canTransition(d, p)).isFalse();
        assertThat(ExternalOrderStatusMachine.canTransition(s, p)).isTrue();
        assertThat(ExternalOrderStatusMachine.canTransition(s, d)).isTrue();
        assertThat(ExternalOrderStatusMachine.canTransition(s, c)).isTrue();
        assertThat(ExternalOrderStatusMachine.canTransition(p, s)).isTrue();
        assertThat(ExternalOrderStatusMachine.canTransition(p, c)).isFalse();
        assertThat(ExternalOrderStatusMachine.canTransition(p, d)).isFalse();
        for (ExternalOrderStatus to : ExternalOrderStatus.values()) {
            assertThat(ExternalOrderStatusMachine.canTransition(c, to)).isFalse();
            assertThat(ExternalOrderStatusMachine.canTransition(to, to)).isFalse();
        }
    }

    @Test
    void illegalTransitionIsConflict() {
        assertThatThrownBy(() -> ExternalOrderStatusMachine.requireTransition(
                ExternalOrderStatus.CANCELLED, ExternalOrderStatus.DRAFT))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("error.externalOrder.status.notAllowed");
                });
    }

    // ---- math ----

    @Test
    void mathSumsLinesAndDelivery() {
        var r = ExternalOrderMath.compute(
                List.of(new ExternalOrderMath.LineInput(bd("10.00"), bd("3"), bd("23")),
                        new ExternalOrderMath.LineInput(bd("1.10"), bd("2.5"), bd("8"))),
                bd("20.00"), bd("23"));
        // 30.00 + 6.90 ; 2.75 + 0.22
        assertThat(r.linesNet()).isEqualByComparingTo("32.75");
        assertThat(r.linesVat()).isEqualByComparingTo("7.12");
        assertThat(r.linesGross()).isEqualByComparingTo("39.87");
        assertThat(r.deliveryGross()).isEqualByComparingTo("24.60");
        assertThat(r.payableGross()).isEqualByComparingTo("64.47");
    }

    @Test
    void mathEmptyAndNoDelivery() {
        var r = ExternalOrderMath.compute(List.of(), null, null);
        assertThat(r.payableGross()).isEqualByComparingTo("0.00");
        assertThat(r.deliveryGross()).isEqualByComparingTo("0.00");
    }

    // ---- coverage ----

    @Test
    void coverageBoundaries() {
        assertThat(ExternalOrderCoverageValidator.isWithin(bd("10"), bd("4"), bd("6"))).isTrue();
        assertThat(ExternalOrderCoverageValidator.isWithin(bd("10"), bd("4"), bd("6.001"))).isFalse();
        assertThat(ExternalOrderCoverageValidator.isWithin(bd("10"), null, bd("10.000"))).isTrue();
        assertThat(ExternalOrderCoverageValidator.remaining(bd("10"), bd("12"))).isEqualByComparingTo("0");
        assertThat(ExternalOrderCoverageValidator.remaining(bd("10"), bd("2.5"))).isEqualByComparingTo("7.5");
        assertThatThrownBy(() -> ExternalOrderCoverageValidator.check(bd("1"), bd("1"), bd("0.001")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getMessage()).contains("error.externalOrder.line.overCoverage"));
    }

    // ---- payment deriver ----

    @Test
    void paymentDeriverRules() {
        List<DocumentPaymentStatus> none = List.of();
        assertThat(ExternalOrderPaymentDeriver.derive(bd("100"), bd("100"), none)).isTrue();
        assertThat(ExternalOrderPaymentDeriver.derive(bd("100"), bd("99.99"), none)).isFalse();
        assertThat(ExternalOrderPaymentDeriver.derive(bd("0"), bd("0"), none)).isFalse();
        assertThat(ExternalOrderPaymentDeriver.derive(bd("100"), bd("0"),
                List.of(DocumentPaymentStatus.PAID, DocumentPaymentStatus.PAID))).isTrue();
        assertThat(ExternalOrderPaymentDeriver.derive(bd("100"), bd("0"),
                List.of(DocumentPaymentStatus.PAID, DocumentPaymentStatus.PARTIALLY_PAID))).isFalse();
        assertThat(ExternalOrderPaymentDeriver.derive(bd("100"), null, null)).isFalse();
    }

    // ---- resolver ----

    @Test
    void resolverSteps() {
        var sent = List.of(ExternalOrderStatus.SENT_FOR_PAYMENT);
        var paid = List.of(ExternalOrderStatus.PAID);
        var mixed = List.of(ExternalOrderStatus.PAID, ExternalOrderStatus.SENT_FOR_PAYMENT);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.WITH_BUYER, bd("5"), bd("5"), sent))
                .isEqualTo(PositionStatus.ORDERED);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.WITH_BUYER, bd("5"), bd("4"), sent))
                .isEqualTo(PositionStatus.WITH_BUYER);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.ORDERED, bd("5"), bd("5"), paid))
                .isEqualTo(PositionStatus.SUPPLIER_PAID);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.ORDERED, bd("5"), bd("5"), mixed))
                .isEqualTo(PositionStatus.ORDERED);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.SUPPLIER_PAID, bd("5"), bd("5"), sent))
                .isEqualTo(PositionStatus.ORDERED);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.SUPPLIER_PAID, bd("5"), bd("2"), paid))
                .isEqualTo(PositionStatus.WITH_BUYER);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.ORDERED, bd("5"), bd("0"), List.of()))
                .isEqualTo(PositionStatus.WITH_BUYER);
        assertThat(PositionOrderStageResolver.resolve(PositionStatus.IN_DELIVERY, bd("5"), bd("0"), List.of()))
                .isEqualTo(PositionStatus.IN_DELIVERY);
    }

    // ---- ceiling ----

    @Test
    void ceilingEdgeCases() {
        assertThat(CeilingCheck.exceeds(bd("10.00"), bd("10.00"))).isFalse();
        assertThat(CeilingCheck.exceeds(bd("10.01"), bd("10.00"))).isTrue();
        assertThat(CeilingCheck.exceeds(bd("9.99"), bd("10.0"))).isFalse();
        assertThat(CeilingCheck.exceeds(bd("10.00"), null)).isFalse();
        assertThat(CeilingCheck.exceeds(null, bd("1"))).isFalse();
        assertThat(CeilingCheck.exceeds(bd("0"), bd("0"))).isFalse();
    }
}
