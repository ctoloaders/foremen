package com.foremen.service.procurement.proforma;

import static com.foremen.service.procurement.proforma.ProformaStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.service.procurement.planner.PositionMoneyMath;

/** Unit tests for the FOR-11-03 proforma pure rules. **Validates: Requirements 2.3, 2.5, 3.4, 8.1, 28.1** */
class ProformaPureRulesTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void machineAllowsDocumentedTransitions() {
        assertThat(ProformaStatusMachine.canMove(DRAFT, ISSUED, null, false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(DRAFT, CANCELLED, null, false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(ISSUED, PARTIALLY_PAID, bd("10"), false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(PARTIALLY_PAID, ISSUED, BigDecimal.ZERO, false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(ISSUED, PAID, bd("100"), false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(PARTIALLY_PAID, PAID, bd("100"), false)).isTrue();
    }

    @Test
    void machineRejectsIllegalTransitions() {
        assertThat(ProformaStatusMachine.canMove(DRAFT, PAID, null, false)).isFalse();
        assertThat(ProformaStatusMachine.canMove(ISSUED, DRAFT, null, false)).isFalse();
        assertThat(ProformaStatusMachine.canMove(PARTIALLY_PAID, CANCELLED, BigDecimal.ZERO, false)).isFalse();
        assertThat(ProformaStatusMachine.canMove(PAID, CANCELLED, bd("100"), false)).isFalse();
        assertThat(ProformaStatusMachine.canMove(CANCELLED, ISSUED, null, false)).isFalse();
        assertThat(ProformaStatusMachine.canMove(null, ISSUED, null, false)).isFalse();
        assertThat(ProformaStatusMachine.isTerminal(CANCELLED)).isTrue();
    }

    @Test
    void cancelIssuedOnlyWhilePaidIsZero() {
        assertThat(ProformaStatusMachine.canMove(ISSUED, CANCELLED, BigDecimal.ZERO, false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(ISSUED, CANCELLED, null, false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(ISSUED, CANCELLED, bd("0.01"), false)).isFalse();
    }

    @Test
    void paidToPartiallyPaidOnlyBeforeRelease() {
        assertThat(ProformaStatusMachine.canMove(PAID, PARTIALLY_PAID, bd("50"), false)).isTrue();
        assertThat(ProformaStatusMachine.canMove(PAID, PARTIALLY_PAID, bd("50"), true)).isFalse();
    }

    @Test
    void deriverBoundaries() {
        BigDecimal total = bd("123.00");
        assertThat(ProformaStatusDeriver.derive(total, bd("0.00"), PARTIALLY_PAID)).isEqualTo(ISSUED);
        assertThat(ProformaStatusDeriver.derive(total, null, ISSUED)).isEqualTo(ISSUED);
        assertThat(ProformaStatusDeriver.derive(total, bd("0.01"), ISSUED)).isEqualTo(PARTIALLY_PAID);
        assertThat(ProformaStatusDeriver.derive(total, bd("122.99"), ISSUED)).isEqualTo(PARTIALLY_PAID);
        assertThat(ProformaStatusDeriver.derive(total, bd("123.00"), ISSUED)).isEqualTo(PAID);
        assertThat(ProformaStatusDeriver.derive(total, bd("200.00"), PARTIALLY_PAID)).isEqualTo(PAID);
        assertThat(ProformaStatusDeriver.derive(total, bd("122.99"), PAID)).isEqualTo(PARTIALLY_PAID);
    }

    @Test
    void deriverNeverMovesDraftOrCancelled() {
        assertThat(ProformaStatusDeriver.derive(bd("10"), bd("10"), DRAFT)).isEqualTo(DRAFT);
        assertThat(ProformaStatusDeriver.derive(bd("10"), bd("10"), CANCELLED)).isEqualTo(CANCELLED);
    }

    @Test
    void numberFormatterPadsYearAndSequence() {
        assertThat(ProformaNumberFormatter.format(2026, 123)).isEqualTo("PF/2026/000123");
        assertThat(ProformaNumberFormatter.format(2026, 1)).isEqualTo("PF/2026/000001");
        assertThat(ProformaNumberFormatter.format(2030, 1234567)).isEqualTo("PF/2030/1234567");
        assertThatThrownBy(() -> ProformaNumberFormatter.format(2026, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mathRoundsPerLineHalfUpAndSumsRoundedLines() {
        // net = 1.005 * 1 -> 1.01 (HALF_UP); vat = 1.01 * 23 / 100 = 0.2323 -> 0.23
        var result = ProformaMath.compute(List.of(
                new ProformaMath.LineInput(bd("1.005"), bd("1"), bd("23")),
                new ProformaMath.LineInput(bd("10.00"), bd("2.500"), bd("8"))));
        assertThat(result.lines().get(0).net()).isEqualByComparingTo("1.01");
        assertThat(result.lines().get(0).vat()).isEqualByComparingTo("0.23");
        assertThat(result.lines().get(1).net()).isEqualByComparingTo("25.00");
        assertThat(result.lines().get(1).vat()).isEqualByComparingTo("2.00");
        assertThat(result.totals().net()).isEqualByComparingTo("26.01");
        assertThat(result.totals().vat()).isEqualByComparingTo("2.23");
        assertThat(result.totals().gross()).isEqualByComparingTo("28.24");
    }

    @Test
    void mathOfEmptyListIsZero() {
        var result = ProformaMath.compute(List.of());
        assertThat(result.totals().gross()).isEqualByComparingTo("0.00");
        assertThat(result.lines()).isEmpty();
    }

    @Test
    void mathRejectsMissingInputs() {
        assertThatThrownBy(() -> ProformaMath.compute(List.of(
                new ProformaMath.LineInput(null, bd("1"), bd("23")))))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void mathMatchesPositionMoneyMathDirectly() {
        var direct = PositionMoneyMath.sum(List.of(PositionMoneyMath.saleLine(bd("9.99"), bd("3"), bd("23"))));
        var viaProforma = ProformaMath.compute(List.of(
                new ProformaMath.LineInput(bd("9.99"), bd("3"), bd("23")))).totals();
        assertThat(viaProforma).isEqualTo(direct);
    }
}
