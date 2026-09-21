package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.VatRateEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link EstimateRecomputeService#recomputeEstimate} — the derivation of
 * an estimate's {@code totalNet}/{@code totalVat}/{@code totalGross} from its lines and VAT rate
 * (FOR-05-03, Requirement 8; design §6.1 {@code recomputeEstimate}).
 *
 * <p>The service is exercised directly as a pure in-memory mutation — no persistence — against a
 * multi-line {@link EstimateEntity} built with generated {@code unitPrice}/room-quantity lines and
 * a generated (possibly absent) {@link VatRateEntity}, so Property 3 is cheap to run over 100+
 * iterations.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 3: Total derivation
 *
 * <p><b>Validates: Requirements 8.2, 8.3, 8.5</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 3: Total derivation")
class EstimateRecomputeServiceTotalDerivationPropertyTest {

    private final EstimateRecomputeService service = new EstimateRecomputeService();

    // ------------------------------------------------------------------------------------------
    // Property 3: totalNet = round2(Σ line.valueNet), totalVat = round2(totalNet × vat / 100),
    // totalGross = round2(totalNet + totalVat)
    // Validates: Requirements 8.2, 8.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 3: Total derivation")
    void totalsAreDerivedFromLineValuesAndVatRate(
            @ForAll("lineSpecs") List<LineSpec> lineSpecs,
            @ForAll("optionalVatRates") BigDecimal vatRatePercent) {

        List<EstimateLineEntity> lines = new ArrayList<>();
        for (LineSpec spec : lineSpecs) {
            lines.add(lineWithRoomQtys(spec.unitPrice, spec.roomQuantities));
        }
        EstimateEntity estimate = estimateWithLines(lines, vatRatePercent);

        service.recomputeEstimate(estimate);

        BigDecimal expectedTotalNet = lines.stream()
                .map(line -> {
                    BigDecimal quantity = line.getRoomQtys().stream()
                            .map(EstimateLineRoomQtyEntity::getQuantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return line.getUnitPrice().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal vat = vatRatePercent != null ? vatRatePercent : BigDecimal.ZERO;
        BigDecimal expectedTotalVat = expectedTotalNet.multiply(vat)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedTotalGross = expectedTotalNet.add(expectedTotalVat)
                .setScale(2, RoundingMode.HALF_UP);

        assertThat(estimate.getTotalNet()).isEqualByComparingTo(expectedTotalNet);
        assertThat(estimate.getTotalVat()).isEqualByComparingTo(expectedTotalVat);
        assertThat(estimate.getTotalGross()).isEqualByComparingTo(expectedTotalGross);

        // totalGross is always the sum of totalNet and totalVat, independent of how they were derived.
        assertThat(estimate.getTotalGross())
                .isEqualByComparingTo(estimate.getTotalNet().add(estimate.getTotalVat()));
    }

    // ------------------------------------------------------------------------------------------
    // Property 3 edge case: an estimate with no lines derives totalNet = totalVat = totalGross = 0
    // Validates: Requirement 8.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 3: Total derivation")
    void estimateWithNoLinesDerivesZeroTotals(@ForAll("optionalVatRates") BigDecimal vatRatePercent) {
        EstimateEntity estimate = estimateWithLines(List.of(), vatRatePercent);

        service.recomputeEstimate(estimate);

        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        assertThat(estimate.getTotalNet()).isEqualByComparingTo(zero);
        assertThat(estimate.getTotalVat()).isEqualByComparingTo(zero);
        assertThat(estimate.getTotalGross()).isEqualByComparingTo(zero);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private EstimateLineEntity lineWithRoomQtys(BigDecimal unitPrice, List<BigDecimal> roomQuantities) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setUnitPrice(unitPrice);

        List<EstimateLineRoomQtyEntity> roomQtys = new ArrayList<>();
        for (BigDecimal quantity : roomQuantities) {
            EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
            roomQty.setLine(line);
            roomQty.setQuantity(quantity);
            roomQtys.add(roomQty);
        }
        line.setRoomQtys(roomQtys);
        return line;
    }

    private EstimateEntity estimateWithLines(List<EstimateLineEntity> lines, BigDecimal vatRatePercent) {
        EstimateEntity estimate = new EstimateEntity();
        for (EstimateLineEntity line : lines) {
            line.setEstimate(estimate);
        }
        estimate.setLines(lines);

        if (vatRatePercent != null) {
            VatRateEntity vatRate = new VatRateEntity();
            vatRate.setRate(vatRatePercent);
            estimate.setVatRate(vatRate);
        }
        return estimate;
    }

    /** A single generated line's inputs: unit price + its room quantities. */
    private static final class LineSpec {
        final BigDecimal unitPrice;
        final List<BigDecimal> roomQuantities;

        LineSpec(BigDecimal unitPrice, List<BigDecimal> roomQuantities) {
            this.unitPrice = unitPrice;
            this.roomQuantities = roomQuantities;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** Unit price snapshots, always present and non-negative (R2.3, precondition of §6.1). */
    @Provide
    Arbitrary<BigDecimal> unitPrices() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("100000.00"))
                .ofScale(2);
    }

    /** A single room quantity, always non-negative (R3.2, precondition of §6.1). */
    @Provide
    Arbitrary<BigDecimal> roomQuantity() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.0000"), new BigDecimal("1000.0000"))
                .ofScale(4);
    }

    /** A line's room quantities, 0..8 rooms, so the zero-quantity line branch is also exercised. */
    @Provide
    Arbitrary<List<BigDecimal>> roomQuantityLists() {
        return roomQuantity().list().ofMinSize(0).ofMaxSize(8);
    }

    /** A single line's generated inputs (unit price + room quantities). */
    @Provide
    Arbitrary<LineSpec> lineSpec() {
        return Combinators.combine(unitPrices(), roomQuantityLists()).as(LineSpec::new);
    }

    /** 0..6 lines per estimate, so the empty-estimate (R8.5) branch is also exercised. */
    @Provide
    Arbitrary<List<LineSpec>> lineSpecs() {
        return lineSpec().list().ofMinSize(0).ofMaxSize(6);
    }

    /**
     * A VAT rate percentage as stored on {@link VatRateEntity#getRate()} (e.g. {@code 23.00} for
     * 23%), or {@code null} to exercise the "no VAT rate assigned" ⇒ {@code vat = 0} branch
     * (R8.3's {@code coalesce(vatRate.rate, 0)} rule).
     */
    @Provide
    Arbitrary<BigDecimal> optionalVatRates() {
        Arbitrary<BigDecimal> rates = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("100.00"))
                .ofScale(2);
        return rates.injectNull(0.2);
    }
}
