package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OfferAction;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.model.mapper.OfferServiceMapper;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for <b>Property 5: Terminal and approved offers are immutable</b> (FOR-05-07,
 * design §Correctness Properties).
 *
 * <p>For all offers in a terminal state ({@code APPROVED}/{@code REJECTED}/{@code WITHDRAWN}), every
 * mutating operation is rejected. The shared entry point every offer mutator consults before writing
 * is {@link OfferService#assertNonTerminal(OfferEntity)} — {@link OfferService#selectPackage} (package
 * change), {@code OfferDiscountService.write}/{@code remove} (discount writes), and
 * {@code NegotiationService} round mutators all route through it — so exercising that shared guard is
 * the single point that captures the terminal-immutability invariant for the discount write, the new
 * negotiation round, the package change, and the client finishing-material choice paths uniformly.
 * The status-machine side of the invariant (no legal exit from a terminal state) is asserted in
 * parallel against {@link OfferStatusMachine}, reinforcing that an {@code APPROVED} offer's agreed
 * version cannot transition (and hence its totals/discounts/package/finishing selection cannot change)
 * afterwards.
 *
 * <p>Both surfaces are exercised as pure functions — {@code assertNonTerminal} reads only the offer's
 * status and the status machine holds no state — so no persistence is needed; the {@link OfferService}
 * collaborators are Mockito stubs that {@code assertNonTerminal} never touches.
 *
 * <p>Feature: FOR-05-07-offer-approval, Property 5: Terminal and approved offers are immutable
 *
 * <p><b>Validates: Requirements 3.9, 7.2, 10.4, 10.8, 12.5</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 5: Terminal and approved offers are immutable")
class TerminalOfferImmutabilityPropertyTest {

    /**
     * The shared 409 code raised by both the terminal-offer guard and an illegal status transition
     * (R3.9 / R10.5) — a terminal-offer write and an illegal transition are indistinguishable.
     */
    private static final String ILLEGAL_TRANSITION = "error.offer.illegal.transition";

    /** The offer service under test, built with Mockito stubs {@code assertNonTerminal} never uses. */
    private final OfferService offerService = newOfferServiceWithStubs();

    /** The pure status machine — the second face of the immutability invariant. */
    private final OfferStatusMachine machine = new OfferStatusMachine();

    // ------------------------------------------------------------------------------------------
    // Property 5a: the shared terminal guard REJECTS every mutation on a terminal offer with 409
    // error.offer.illegal.transition, and the offer's status is left unchanged.
    // Validates: Requirements 3.9, 7.2, 10.4, 10.8, 12.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 5: Terminal and approved offers are immutable")
    void terminalOfferRejectsEveryMutation(@ForAll("terminalStatuses") OfferStatus terminal) {
        OfferEntity offer = offerWithStatus(terminal);

        assertThatThrownBy(() -> offerService.assertNonTerminal(offer))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException fe = (ForemenApiException) ex;
                    assertThat(fe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(fe.getMessageCode()).isEqualTo(ILLEGAL_TRANSITION);
                });

        // Rejection happens before any write, so the offer is unchanged (its status is still terminal).
        assertThat(offer.getStatus()).isEqualTo(terminal);
        assertThat(offer.getStatus().isTerminal()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Property 5b: the shared terminal guard PERMITS a mutation on any non-terminal offer (the
    // guard only blocks terminal states; it is not a blanket denial).
    // Validates: Requirements 3.9 (guard is exactly the terminal predicate)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 5: Terminal and approved offers are immutable")
    void nonTerminalOfferPassesTheGuard(@ForAll("nonTerminalStatuses") OfferStatus nonTerminal) {
        OfferEntity offer = offerWithStatus(nonTerminal);

        assertThatCode(() -> offerService.assertNonTerminal(offer)).doesNotThrowAnyException();
        assertThat(offer.getStatus().isTerminal()).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Property 5c: the guard's rejection is exactly the terminal predicate over every status,
    // including a null status (treated as non-terminal / passable, matching the guard's null-safe
    // branch). This pins the guard to OfferStatus.isTerminal() for the whole status domain.
    // Validates: Requirements 3.9, 10.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 5: Terminal and approved offers are immutable")
    void guardMatchesTheTerminalPredicateForEveryStatus(@ForAll("statusesWithNull") OfferStatus status) {
        OfferEntity offer = offerWithStatus(status);
        boolean shouldReject = status != null && status.isTerminal();

        if (shouldReject) {
            assertThatThrownBy(() -> offerService.assertNonTerminal(offer))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> assertThat(((ForemenApiException) ex).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        } else {
            assertThatCode(() -> offerService.assertNonTerminal(offer)).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 5d: the status machine allows NO transition out of a terminal state, for any action
    // and any actor role. This is the second face of immutability: an APPROVED offer's agreed
    // version cannot transition, so it cannot change afterwards (R7.2 / R10.8).
    // Validates: Requirements 3.9, 7.2, 10.4, 10.8
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 5: Terminal and approved offers are immutable")
    void statusMachineAllowsNoExitFromTerminalState(@ForAll("terminalStatuses") OfferStatus terminal,
                                                    @ForAll("actions") OfferAction action,
                                                    @ForAll("actorRoles") String actorRole) {
        assertThat(machine.canTransition(terminal, action, actorRole)).isFalse();

        assertThatThrownBy(() -> machine.transition(terminal, action, actorRole))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException fe = (ForemenApiException) ex;
                    assertThat(fe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(fe.getMessageCode()).isEqualTo(ILLEGAL_TRANSITION);
                });
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static OfferEntity offerWithStatus(OfferStatus status) {
        OfferEntity offer = new OfferEntity();
        offer.setStatus(status);
        return offer;
    }

    /**
     * Builds an {@link OfferService} with Mockito stub collaborators. Property 5 exercises only
     * {@link OfferService#assertNonTerminal(OfferEntity)}, which reads solely the passed offer's
     * status and touches no collaborator, so the stubs are never invoked.
     */
    @SuppressWarnings("unchecked")
    private static OfferService newOfferServiceWithStubs() {
        return new OfferService(
                mock(OfferDao.class),
                mock(EstimateDao.class),
                mock(EstimateLineRoomMaterialDao.class),
                mock(OfferPackageDao.class),
                mock(UserDao.class),
                mock(OfferServiceMapper.class),
                mock(ProjectAccessCache.class),
                mock(AuditLogDao.class),
                mock(EntityManager.class),
                new OfferStatusMachine(),
                mock(OfferTotalsCalculator.class),
                mock(DiscountResolver.class),
                mock(DraftGateGuard.class),
                mock(EstimateAssignmentService.class),
                mock(ApplicationEventPublisher.class),
                mock(ClientOfferReadModelAssembler.class));
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** The three terminal statuses only. */
    @Provide
    Arbitrary<OfferStatus> terminalStatuses() {
        return Arbitraries.of(OfferStatus.APPROVED, OfferStatus.REJECTED, OfferStatus.WITHDRAWN);
    }

    /** The non-terminal statuses only. */
    @Provide
    Arbitrary<OfferStatus> nonTerminalStatuses() {
        return Arbitraries.of(OfferStatus.class).filter(s -> !s.isTerminal());
    }

    /** Every offer status, plus {@code null} to exercise the guard's null-safe branch. */
    @Provide
    Arbitrary<OfferStatus> statusesWithNull() {
        return Arbitraries.of(OfferStatus.class).injectNull(0.1);
    }

    /** Every action, plus {@code null} to exercise the null-action rejection branch. */
    @Provide
    Arbitrary<OfferAction> actions() {
        return Arbitraries.of(OfferAction.class).injectNull(0.1);
    }

    /**
     * Actor role codes: the recognized executor/client roles plus unrecognized roles and
     * {@code null} — no role may transition out of a terminal state.
     */
    @Provide
    Arbitrary<String> actorRoles() {
        return Arbitraries.of("MANAGER", "ADMIN", "CLIENT", "FOREMAN", "WORKER", "FINANCIER", "")
                .injectNull(0.1);
    }
}
