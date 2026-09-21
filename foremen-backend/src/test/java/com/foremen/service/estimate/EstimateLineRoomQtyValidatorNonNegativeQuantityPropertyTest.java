package com.foremen.service.estimate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.exception.ForemenApiException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link EstimateLineRoomQtyValidator#validateRoomQty} — the
 * non-negative quantity rule (FOR-05-03, Requirement 3.2; design §6.5 {@code
 * validateRoomQty}).
 *
 * <p>Builds minimal in-memory entity graphs (a {@code line -> estimate -> project} chain and a
 * {@code room -> project} chain, both with generated ids only — no persistence) and exercises
 * the validator directly as a pure function of the entity graph, mirroring the
 * {@code EstimateLineRoomQtyValidatorCrossProjectPropertyTest} convention of testing a
 * stateless {@code @Component} without Spring context or a database.
 *
 * <p>Project ids are held fixed and equal on both sides of the graph (line's estimate project
 * == room's project) across every case in this property so that a thrown exception can only be
 * attributed to the non-negative rule, never to the sibling cross-project rule (design §6.5
 * checks cross-project first, then non-negative).
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 9: Non-negative quantities
 *
 * <p><b>Validates: Requirements 3.2, 3.4</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 9: Non-negative quantities")
class EstimateLineRoomQtyValidatorNonNegativeQuantityPropertyTest {

    private static final String NEGATIVE_QTY_MESSAGE = "error.estimate.qty.negative";

    private final EstimateLineRoomQtyValidator validator = new EstimateLineRoomQtyValidator();

    // ------------------------------------------------------------------------------------------
    // Property 9a: any negative quantity is always rejected with the negative-qty message
    // Validates: Requirement 3.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 9: Non-negative quantities")
    void negativeQuantitiesAreAlwaysRejected(@ForAll("projectIds") Long projectId,
                                              @ForAll("negativeQuantities") BigDecimal quantity) {
        EstimateLineRoomQtyEntity roomQty = buildRoomQty(projectId, projectId, quantity);

        assertThatThrownBy(() -> validator.validateRoomQty(roomQty))
                .isInstanceOf(ForemenApiException.class)
                .hasMessage(NEGATIVE_QTY_MESSAGE)
                .extracting(ex -> ((ForemenApiException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------------------------------
    // Property 9b: any non-negative quantity never throws for this reason
    // Validates: Requirement 3.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 9: Non-negative quantities")
    void nonNegativeQuantitiesNeverThrowNegativeQtyRejection(@ForAll("projectIds") Long projectId,
                                                              @ForAll("nonNegativeQuantities") BigDecimal quantity) {
        EstimateLineRoomQtyEntity roomQty = buildRoomQty(projectId, projectId, quantity);

        // Project ids are held equal, so the sibling cross-project rule can never fire here --
        // no exception of any kind is expected on the non-negative path.
        assertThat(catchException(() -> validator.validateRoomQty(roomQty))).isNull();
    }

    private static Exception catchException(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Entity graph construction
    // ------------------------------------------------------------------------------------------

    private static EstimateLineRoomQtyEntity buildRoomQty(long lineProjectId, long roomProjectId, BigDecimal quantity) {
        ProjectEntity lineProject = new ProjectEntity();
        lineProject.setId(lineProjectId);

        EstimateEntity estimate = new EstimateEntity();
        estimate.setProject(lineProject);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);

        ProjectEntity roomProject = new ProjectEntity();
        roomProject.setId(roomProjectId);

        RoomEntity room = new RoomEntity();
        room.setProject(roomProject);

        EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
        roomQty.setLine(line);
        roomQty.setRoom(room);
        roomQty.setQuantity(quantity);
        return roomQty;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** Small, dense id space; only used here to keep line/room project ids equal. */
    @Provide
    Arbitrary<Long> projectIds() {
        return Arbitraries.longs().between(1L, 1_000L);
    }

    /** Strictly negative quantities only, so the non-negative rule is always the one that fires. */
    @Provide
    Arbitrary<BigDecimal> negativeQuantities() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-100000.0000"), new BigDecimal("-0.0001"))
                .ofScale(4);
    }

    /** Non-negative quantities only, so the negative-qty rule never fires. */
    @Provide
    Arbitrary<BigDecimal> nonNegativeQuantities() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000.0000"))
                .ofScale(4);
    }
}
