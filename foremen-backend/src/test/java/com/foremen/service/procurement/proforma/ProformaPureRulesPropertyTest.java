package com.foremen.service.procurement.proforma;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.foremen.service.procurement.planner.PositionMoneyMath;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/** **Validates: Requirements 2.3, 2.5, 3.4, 8.1, 28.1** */
class ProformaPureRulesPropertyTest {

    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("100000")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> unitPrice() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("5000")).ofScale(3);
    }

    @Provide
    Arbitrary<BigDecimal> quantity() {
        return Arbitraries.bigDecimals().between(new BigDecimal("0.001"), new BigDecimal("500")).ofScale(3);
    }

    @Provide
    Arbitrary<BigDecimal> vat() {
        return Arbitraries.of(new BigDecimal("0"), new BigDecimal("5"), new BigDecimal("8"), new BigDecimal("23"));
    }

    @Provide
    Arbitrary<List<ProformaMath.LineInput>> inputs() {
        return unitPrice().flatMap(p -> quantity().flatMap(q -> vat().map(v -> new ProformaMath.LineInput(p, q, v))))
                .list().ofMaxSize(15);
    }

    @Property
    void grossEqualsPlannerTotalForSamePositions(@ForAll("inputs") List<ProformaMath.LineInput> in) {
        List<PositionMoneyMath.Line> planner = new ArrayList<>();
        for (var i : in) {
            planner.add(PositionMoneyMath.saleLine(i.unitSaleNet(), i.quantity(), i.vatRatePercent()));
        }
        var expected = PositionMoneyMath.sum(planner);
        var actual = ProformaMath.compute(in).totals();
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.gross()).isEqualByComparingTo(actual.net().add(actual.vat()));
    }

    @Property
    void totalIsOrderIndependent(@ForAll("inputs") List<ProformaMath.LineInput> in) {
        List<ProformaMath.LineInput> reversed = new ArrayList<>(in);
        Collections.reverse(reversed);
        assertThat(ProformaMath.compute(reversed).totals()).isEqualTo(ProformaMath.compute(in).totals());
    }

    @Property
    void deriverMatchesRules(@ForAll("money") BigDecimal total, @ForAll("money") BigDecimal paid) {
        for (ProformaStatus current : Set.of(ProformaStatus.ISSUED, ProformaStatus.PARTIALLY_PAID,
                ProformaStatus.PAID)) {
            ProformaStatus expected;
            if (paid.signum() == 0) expected = ProformaStatus.ISSUED;
            else if (paid.compareTo(total) >= 0) expected = ProformaStatus.PAID;
            else expected = ProformaStatus.PARTIALLY_PAID;
            assertThat(ProformaStatusDeriver.derive(total, paid, current)).isEqualTo(expected);
        }
    }

    @Property
    void deriverIsIdempotent(@ForAll("money") BigDecimal total, @ForAll("money") BigDecimal paid) {
        ProformaStatus once = ProformaStatusDeriver.derive(total, paid, ProformaStatus.ISSUED);
        assertThat(ProformaStatusDeriver.derive(total, paid, once)).isEqualTo(once);
    }

    @Property
    void draftAndCancelledAreFixed(@ForAll("money") BigDecimal total, @ForAll("money") BigDecimal paid) {
        assertThat(ProformaStatusDeriver.derive(total, paid, ProformaStatus.DRAFT)).isEqualTo(ProformaStatus.DRAFT);
        assertThat(ProformaStatusDeriver.derive(total, paid, ProformaStatus.CANCELLED))
                .isEqualTo(ProformaStatus.CANCELLED);
    }

    @Property
    void machineNeverLeavesCancelledOrReturnsToDraft(@ForAll ProformaStatus from, @ForAll ProformaStatus to,
                                                     @ForAll("money") BigDecimal paid, @ForAll boolean released) {
        boolean ok = ProformaStatusMachine.canMove(from, to, paid, released);
        if (from == ProformaStatus.CANCELLED || to == ProformaStatus.DRAFT || from == to) {
            assertThat(ok).isFalse();
        }
        if (ok) {
            assertThat(ProformaStatusMachine.exits(from)).contains(to);
        }
    }

    @Property
    void numberHasFixedShape(@ForAll @IntRange(min = 1000, max = 9999) int year,
                             @ForAll @LongRange(min = 0, max = 999999) long seq) {
        String n = ProformaNumberFormatter.format(year, seq);
        assertThat(n).matches("PF/\\d{4}/\\d{6}");
        assertThat(n).isEqualTo("PF/" + year + "/" + String.format("%06d", seq));
    }
}
