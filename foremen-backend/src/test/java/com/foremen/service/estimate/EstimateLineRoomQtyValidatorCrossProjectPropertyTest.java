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
 * cross-project rejection rule (FOR-05-03, Requirement 3.6; design §6.5 {@code
 * validateRoomQty}).
 *
 * <p>Builds minimal in-memory entity graphs (a {@code line -> estimate -> project} chain and a
 * {@code room -> project} chain, both with generated ids only — no persistence) and exercises
 * the validator directly as a pure function of the entity graph, mirroring the
 * {@code DiscountCalculatorPropertyTest} convention of testing a stateless {@code @Component}
 * without Spring context or a database.
 *
 * <p>Quantity is held fixed at a non-negative value across every case in this property so that
 * a thrown exception can only be attributed to the cross-project rule, never to the sibling
 * negative-quantity rule (design §6.5 checks cross-project first, then non-negative).
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 10: Cross-project rejection
 *
 * <p><b>Validates: Requirements 3.6</b>
 */
@Tag("Feature: FOR-05-03-estimate-core, Property 10: Cross-project rejection")
class EstimateLineRoomQtyValidatorCrossProjectPropertyTest {

    private static final String CROSS_PROJECT_MESSAGE = "error.estimate.room.cross.project";

    private final EstimateLineRoomQtyValidator validator = new EstimateLineRoomQtyValidator();

    // ------------------------------------------------------------------------------------------
    // Property 10a: mismatched project ids are always rejected with the cross-project message
    // Validates: Requirement 3.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 10: Cross-project rejection")
    void mismatchedProjectIdsAreAlwaysRejected(@ForAll("projectIdPairs") ProjectIdPair ids,
                                                @ForAll("nonNegativeQuantities") BigDecimal quantity) {
        EstimateLineRoomQtyEntity roomQty = buildRoomQty(ids.lineProjectId(), ids.roomProjectId(), quantity);

        assertThatThrownBy(() -> validator.validateRoomQty(roomQty))
                .isInstanceOf(ForemenApiException.class)
                .hasMessage(CROSS_PROJECT_MESSAGE)
                .extracting(ex -> ((ForemenApiException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------------------------------
    // Property 10b: matching project ids never throw the cross-project rejection
    // Validates: Requirement 3.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-03-estimate-core, Property 10: Cross-project rejection")
    void matchingProjectIdsNeverThrowCrossProjectRejection(@ForAll("projectIds") Long projectId,
                                                            @ForAll("nonNegativeQuantities") BigDecimal quantity) {
        EstimateLineRoomQtyEntity roomQty = buildRoomQty(projectId, projectId, quantity);

        // Quantity is constrained non-negative, so the sibling rule can never fire here --
        // no exception of any kind is expected on the matching-project path.
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

    private record ProjectIdPair(long lineProjectId, long roomProjectId) {
    }

    /** Small, dense id space so mismatches are frequent and the property is cheap to falsify. */
    @Provide
    Arbitrary<Long> projectIds() {
        return Arbitraries.longs().between(1L, 1_000L);
    }

    /** Pairs of project ids guaranteed to differ (the mismatch case under test). */
    @Provide
    Arbitrary<ProjectIdPair> projectIdPairs() {
        return Arbitraries.longs().between(1L, 1_000L)
                .flatMap(lineProjectId -> Arbitraries.longs().between(1L, 1_000L)
                        .filter(roomProjectId -> !roomProjectId.equals(lineProjectId))
                        .map(roomProjectId -> new ProjectIdPair(lineProjectId, roomProjectId)));
    }

    /** Non-negative quantities only, so the sibling negative-quantity rule never interferes. */
    @Provide
    Arbitrary<BigDecimal> nonNegativeQuantities() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000.0000"))
                .ofScale(4);
    }
}
