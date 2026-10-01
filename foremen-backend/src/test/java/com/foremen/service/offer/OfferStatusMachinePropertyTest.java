package com.foremen.service.offer;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.http.HttpStatus;

import com.foremen.dao.model.OfferAction;
import com.foremen.dao.model.OfferStatus;
import com.foremen.exception.ForemenApiException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link OfferStatusMachine#transition(OfferStatus, OfferAction, String)} —
 * the pure, stateless offer transition function (FOR-05-07, Requirement 3; design §OfferStatusMachine).
 *
 * <p>The machine is exercised directly as a pure function — no persistence — so Property 4 is cheap
 * to run over 100+ iterations of arbitrary {@code (status, action, actorRole)} triples.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 4: The status machine performs no illegal transition
 *
 * <p><b>Validates: Requirements 3.8, 10.5</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 4: The status machine performs no illegal transition")
class OfferStatusMachinePropertyTest {

    private final OfferStatusMachine machine = new OfferStatusMachine();

    /** Localized message code for a rejected transition (R3.8 / R10.5). */
    private static final String ILLEGAL_TRANSITION = "error.offer.illegal.transition";

    private static final Set<String> EXECUTOR_ROLES = Set.of("MANAGER", "ADMIN");
    private static final String CLIENT_ROLE = "CLIENT";
    private static final Set<OfferStatus> NEGOTIABLE = EnumSet.of(OfferStatus.SENT, OfferStatus.COUNTERED);

    // ------------------------------------------------------------------------------------------
    // Property 4: transition is applied IFF it is a Requirement-3 legal transition, and every
    // undefined triple (including any exit from a terminal state) is rejected with the 409
    // illegal-transition error, leaving the offer unchanged.
    // Validates: Requirements 3.8, 10.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-07-offer-approval, Property 4: The status machine performs no illegal transition")
    void transitionAppliedIffLegalOtherwiseRejected(@ForAll("statuses") OfferStatus current,
                                                     @ForAll("actions") OfferAction action,
                                                     @ForAll("actorRoles") String actorRole) {
        OfferStatus expectedNext = oracle(current, action, actorRole);

        if (expectedNext == null) {
            // Undefined transition -> rejected with 409 error.offer.illegal.transition; the machine
            // holds no state, so "leaves the offer unchanged" is guaranteed by throwing before any
            // status is produced. canTransition must agree.
            assertThatThrownBy(() -> machine.transition(current, action, actorRole))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException fe = (ForemenApiException) ex;
                        assertThat(fe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(fe.getMessageCode()).isEqualTo(ILLEGAL_TRANSITION);
                    });
            assertThat(machine.canTransition(current, action, actorRole)).isFalse();
        } else {
            // Legal transition -> the machine returns exactly the oracle's next state, that state is
            // one of the legally-defined targets, and canTransition agrees.
            OfferStatus actual = machine.transition(current, action, actorRole);
            assertThat(actual).isEqualTo(expectedNext);
            assertThat(actual).isIn(legalTargets());
            assertThat(machine.canTransition(current, action, actorRole)).isTrue();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 4a: NO action ever succeeds out of a terminal state (APPROVED/REJECTED/WITHDRAWN),
    // regardless of action or actor role. This is the immutability edge of R3.8.
    // Validates: Requirements 3.8, 10.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 4: The status machine performs no illegal transition")
    void terminalStatesAreInescapable(@ForAll("terminalStatuses") OfferStatus terminal,
                                      @ForAll("actions") OfferAction action,
                                      @ForAll("actorRoles") String actorRole) {
        assertThat(machine.canTransition(terminal, action, actorRole)).isFalse();

        assertThatThrownBy(() -> machine.transition(terminal, action, actorRole))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b: the machine is deterministic -- the same triple always yields the same outcome
    // (either the same next status, or the same rejection).
    // Validates: Requirements 3.8, 10.5 (purity underlying the machine)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 4: The status machine performs no illegal transition")
    void isDeterministic(@ForAll("statuses") OfferStatus current,
                         @ForAll("actions") OfferAction action,
                         @ForAll("actorRoles") String actorRole) {
        boolean firstLegal = machine.canTransition(current, action, actorRole);
        boolean secondLegal = machine.canTransition(current, action, actorRole);
        assertThat(secondLegal).isEqualTo(firstLegal);

        if (firstLegal) {
            OfferStatus first = machine.transition(current, action, actorRole);
            OfferStatus second = machine.transition(current, action, actorRole);
            assertThat(second).isEqualTo(first);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Independent oracle of the Requirement-3 legal transition set. Deliberately written from the
    // requirements text (not by delegating to the machine) so it is a genuine cross-check.
    //   DRAFT             + SEND            by executor -> SENT              (R3.2)
    //   SENT/COUNTERED    + REQUEST_CHANGES by client   -> CHANGES_REQUESTED (R3.3)
    //   CHANGES_REQUESTED + PROPOSE         by executor -> COUNTERED         (R3.4)
    //   SENT/COUNTERED    + APPROVE         by client   -> APPROVED          (R3.5)
    //   SENT/COUNTERED    + REJECT          by client   -> REJECTED          (R3.6)
    //   any non-terminal  + WITHDRAW        by executor -> WITHDRAWN         (R3.7)
    // Everything else (null status/action, terminal source, wrong role) is illegal (R3.8).
    // ------------------------------------------------------------------------------------------
    private static OfferStatus oracle(OfferStatus current, OfferAction action, String actorRole) {
        if (current == null || action == null || current.isTerminal()) {
            return null;
        }
        boolean executor = actorRole != null && EXECUTOR_ROLES.contains(actorRole);
        boolean client = CLIENT_ROLE.equals(actorRole);
        boolean negotiable = NEGOTIABLE.contains(current);

        return switch (action) {
            case SEND -> executor && current == OfferStatus.DRAFT ? OfferStatus.SENT : null;
            case REQUEST_CHANGES -> client && negotiable ? OfferStatus.CHANGES_REQUESTED : null;
            case PROPOSE -> executor && current == OfferStatus.CHANGES_REQUESTED ? OfferStatus.COUNTERED : null;
            case APPROVE -> client && negotiable ? OfferStatus.APPROVED : null;
            case REJECT -> client && negotiable ? OfferStatus.REJECTED : null;
            case WITHDRAW -> executor ? OfferStatus.WITHDRAWN : null;
        };
    }

    private static Set<OfferStatus> legalTargets() {
        return EnumSet.of(OfferStatus.SENT, OfferStatus.CHANGES_REQUESTED, OfferStatus.COUNTERED,
                OfferStatus.APPROVED, OfferStatus.REJECTED, OfferStatus.WITHDRAWN);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** Every offer status, plus {@code null} to exercise the null-source rejection branch. */
    @Provide
    Arbitrary<OfferStatus> statuses() {
        return Arbitraries.of(OfferStatus.class).injectNull(0.1);
    }

    /** The three terminal statuses only. */
    @Provide
    Arbitrary<OfferStatus> terminalStatuses() {
        return Arbitraries.of(OfferStatus.APPROVED, OfferStatus.REJECTED, OfferStatus.WITHDRAWN);
    }

    /** Every action, plus {@code null} to exercise the null-action rejection branch. */
    @Provide
    Arbitrary<OfferAction> actions() {
        return Arbitraries.of(OfferAction.class).injectNull(0.1);
    }

    /**
     * Actor role codes: the recognized executor/client roles plus unrecognized roles and
     * {@code null}, so both the authorized and the unauthorized-actor branches are exercised.
     */
    @Provide
    Arbitrary<String> actorRoles() {
        return Arbitraries.of("MANAGER", "ADMIN", "CLIENT", "FOREMAN", "WORKER", "FINANCIER", "")
                .injectNull(0.1);
    }
}
