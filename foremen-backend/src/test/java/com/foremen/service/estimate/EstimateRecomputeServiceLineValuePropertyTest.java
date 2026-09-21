package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link EstimateRecomputeService#recomputeEstimate} — the derivation of
 * a line's {@code quantity}/{@code valueNet} from its room quantities and unit price (FOR-05-03,
 * Requirement 2; design §6.1 {@code recomputeEstimate}).
 *
 * <p>The service is exercised directly as a pure in-memory mutation — no persistence — against a
 * single-line {@link EstimateEntity} built with a generated {@code unitPrice} and a generated list
 * of {@link EstimateLineRoomQtyEntity} room quantities, so Property 2 is cheap to run over 100+
 * iterations.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 2: Line value derivation
 *
 * <p><b>Validates: Requirements 2.6, 2.7, 8.1</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 2: Line value derivation")
class EstimateRecomputeServiceLineValuePropertyTest {

    private final EstimateRecomputeService service = new EstimateRecomputeService();

    // ------------------------------------------------------------------------------------------
    // Property 2: line.quantity = Σ roomQty.quantity and line.valueNet = round2(unitPrice × qty)
    // Validates: Requirements 2.6, 2.7, 8.1
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 2: Line value derivation")
    void lineQuantityIsSumOfRoomQuantitiesAndValueNetIsUnitPriceTimesQuantity(
            @ForAll("unitPrices") BigDecimal unitPrice,
            @ForAll("roomQuantityLists") List<BigDecimal> roomQuantities) {

        EstimateLineEntity line = lineWithRoomQtys(unitPrice, roomQuantities);
        EstimateEntity estimate = estimateWithLines(List.of(line));

        service.recomputeEstimate(estimate);

        BigDecimal expectedQuantity = roomQuantities.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedValueNet = unitPrice.multiply(expectedQuantity)
                .setScale(2, RoundingMode.HALF_UP);

        assertThat(line.getQuantity()).isEqualByComparingTo(expectedQuantity);
        assertThat(line.getValueNet()).isEqualByComparingTo(expectedValueNet);
    }

    // ------------------------------------------------------------------------------------------
    // Property 2 edge case: a line with no room quantities derives quantity = 0, valueNet = 0
    // Validates: Requirement 3.4 (feeding Property 2's zero-quantity branch)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 2: Line value derivation")
    void lineWithNoRoomQuantitiesDerivesZeroQuantityAndZeroValue(@ForAll("unitPrices") BigDecimal unitPrice) {
        EstimateLineEntity line = lineWithRoomQtys(unitPrice, List.of());
        EstimateEntity estimate = estimateWithLines(List.of(line));

        service.recomputeEstimate(estimate);

        assertThat(line.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.getValueNet()).isEqualByComparingTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
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

    private EstimateEntity estimateWithLines(List<EstimateLineEntity> lines) {
        EstimateEntity estimate = new EstimateEntity();
        for (EstimateLineEntity line : lines) {
            line.setEstimate(estimate);
        }
        estimate.setLines(lines);
        return estimate;
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

    /** A line's room quantities, 0..8 rooms, so the empty-list (R3.4) branch is also exercised. */
    @Provide
    Arbitrary<List<BigDecimal>> roomQuantityLists() {
        return roomQuantity().list().ofMinSize(0).ofMaxSize(8);
    }
}
