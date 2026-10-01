package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferDiscountDao;
import com.foremen.dao.OfferNegotiationRoundDao;
import com.foremen.dao.OfferProjectSettingsDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.NegotiationRoundStatus;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EscalationPolicy;
import com.foremen.service.OfferService;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterProperty;

/**
 * Property-based tests for the two cross-cutting {@link NegotiationService} guards that every
 * negotiation mutator applies (FOR-05-07, design §Property 15 and §Property 12).
 *
 * <p><b>Chosen test level.</b> The negotiation mutators are not pure functions — each resolves the
 * offer/round from a DAO, resolves the acting role from the {@link SecurityContextHolder}, delegates
 * the terminal-offer guard to {@link OfferService}, and persists — so they are exercised at the
 * service level with <b>mocked DAOs</b> ({@link OfferDao}, {@link OfferNegotiationRoundDao},
 * {@link OfferDiscountDao}, {@link OfferProjectSettingsDao}, {@link UserDao}), a <b>mocked
 * {@link OfferService}</b> (so {@code assertNonTerminal} is a no-op — the offer is non-terminal for
 * these properties, isolating the round-state / explanation guards under test), a <b>mocked
 * {@link EscalationPolicy}</b> and a <b>mocked {@link EntityManager}</b>, plus a manually-driven
 * {@link SecurityContextHolder} authenticated as the role the mutator requires. This mirrors the
 * {@code OfferServicePrepareOfferPropertyTest} / {@code OfferDiscountServiceTest} conventions and
 * runs entirely in memory over 100+ iterations with no Spring context and no Testcontainers.
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 15: Acting on a resolved round is rejected</b> —
 * <b>Validates: Requirements 4.7</b>
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 12: A manager rejection requires a non-blank
 * explanation</b> — <b>Validates: Requirements 4.8, 6.6, 10.17</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 15: Acting on a resolved round is rejected")
@Tag("Feature: FOR-05-07-offer-approval, Property 12: A manager rejection requires a non-blank explanation")
class NegotiationServiceGuardsPropertyTest {

    private static final long OFFER_ID = 700L;
    private static final long ROUND_ID = 800L;
    private static final long MANAGER_USER_ID = 11L;
    private static final long CLIENT_USER_ID = 22L;

    private static final String ROUND_RESOLVED_MESSAGE = "error.offer.round.resolved";
    private static final String REJECT_EXPLANATION_REQUIRED_MESSAGE =
            "error.offer.reject.explanation.required";

    // ------------------------------------------------------------------------------------------
    // Property 15: Acting on a resolved round is rejected.
    // For any round already in a resolved status (ACCEPTED / DECLINED / REJECTED / SUPERSEDED), any
    // mutator acting on it (managerPropose / managerReject / clientAccept / clientDecline) is
    // rejected with 409 error.offer.round.resolved and NO state change is persisted (no round or
    // discount saved, no offer save). The offer is non-terminal, so the round-state guard — not the
    // terminal-offer guard — is what fires.
    // Validates: Requirements 4.7
    // ------------------------------------------------------------------------------------------

    // The combined parameter space (4 statuses x 4 mutators) is small enough that jqwik would switch
    // to EXHAUSTIVE generation and cap at 16 tries; force RANDOMIZED so the property runs >= 100
    // iterations while still mixing in every edge case.
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    @Tag("Feature: FOR-05-07-offer-approval, Property 15: Acting on a resolved round is rejected")
    void actingOnResolvedRoundIsRejected(
            @ForAll("resolvedStatuses") NegotiationRoundStatus resolvedStatus,
            @ForAll("mutators") Mutator mutator) {

        Fixture f = new Fixture();
        // The mutator requires a specific role; authenticate accordingly.
        if (mutator.managerRole()) {
            f.authenticateManager();
        } else {
            f.authenticateClient();
        }

        OfferEntity offer = nonTerminalOffer();
        // A MANAGER_PROPOSAL so clientAccept/clientDecline pass the round-kind check ONLY if the
        // round were OPEN — but here it is resolved, so the round-state guard fires first regardless.
        OfferNegotiationRoundEntity round =
                round(NegotiationRoundKind.MANAGER_PROPOSAL, resolvedStatus, offer);
        offer.getNegotiationRounds().add(round);

        when(f.roundDao.findById(ROUND_ID)).thenReturn(Optional.of(round));

        ForemenApiException ex = catchThrowableOfType(
                () -> mutator.act(f.service), ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessageCode()).isEqualTo(ROUND_RESOLVED_MESSAGE);

        // No state change: neither a new round nor a discount is persisted, and the offer is untouched.
        verify(f.roundDao, never()).save(any());
        verify(f.offerDiscountDao, never()).save(any());
        verify(f.offerDao, never()).save(any());
        // The round's own status is unchanged.
        assertThat(round.getStatus()).isEqualTo(resolvedStatus);
    }

    // ------------------------------------------------------------------------------------------
    // Property 12: A manager rejection requires a non-blank explanation.
    // For any blank explanation (null / "" / all-whitespace), managerReject is rejected 400
    // error.offer.reject.explanation.required and NO MANAGER_REJECT round is recorded; for any
    // non-blank explanation the rejection is accepted and a MANAGER_REJECT round is recorded.
    // Validates: Requirements 4.8, 6.6, 10.17
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 12: A manager rejection requires a non-blank explanation")
    void blankExplanationIsRejectedAndNoRejectRecorded(@ForAll("blankExplanations") String explanation) {
        Fixture f = new Fixture();
        f.authenticateManager();

        OfferEntity offer = nonTerminalOffer();
        OfferNegotiationRoundEntity request =
                round(NegotiationRoundKind.DISCOUNT_REQUEST, NegotiationRoundStatus.OPEN, offer);
        offer.getNegotiationRounds().add(request);
        when(f.roundDao.findById(ROUND_ID)).thenReturn(Optional.of(request));

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.managerReject(ROUND_ID, explanation), ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo(REJECT_EXPLANATION_REQUIRED_MESSAGE);

        // No MANAGER_REJECT round recorded and the open request is left untouched.
        verify(f.roundDao, never()).save(any());
        verify(f.offerDao, never()).save(any());
        assertThat(request.getStatus()).isEqualTo(NegotiationRoundStatus.OPEN);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 12: A manager rejection requires a non-blank explanation")
    void nonBlankExplanationIsAcceptedAndRejectRecorded(@ForAll("nonBlankExplanations") String explanation) {
        Fixture f = new Fixture();
        f.authenticateManager();

        OfferEntity offer = nonTerminalOffer();
        OfferNegotiationRoundEntity request =
                round(NegotiationRoundKind.DISCOUNT_REQUEST, NegotiationRoundStatus.OPEN, offer);
        offer.getNegotiationRounds().add(request);
        when(f.roundDao.findById(ROUND_ID)).thenReturn(Optional.of(request));

        OfferNegotiationRoundEntity rejection = f.service.managerReject(ROUND_ID, explanation);

        // A MANAGER_REJECT round carrying the non-blank explanation is recorded.
        assertThat(rejection).isNotNull();
        assertThat(rejection.getKind()).isEqualTo(NegotiationRoundKind.MANAGER_REJECT);
        assertThat(rejection.getExplanation()).isEqualTo(explanation);
        assertThat(rejection.getStatus()).isEqualTo(NegotiationRoundStatus.REJECTED);
        // The answered request is resolved as REJECTED.
        assertThat(request.getStatus()).isEqualTo(NegotiationRoundStatus.REJECTED);
        verify(f.roundDao).save(any());
    }

    // ---- Mutator abstraction (Property 15) ----

    /**
     * A negotiation mutator acting on an existing round, tagged with the role it requires. The four
     * round-acting mutators all funnel through the shared {@code assertRoundOpen} guard, so each must
     * reject a resolved round.
     */
    private interface Mutator {
        void act(NegotiationService service);

        /** Whether the mutator is a MANAGER/ADMIN action (else a CLIENT action). */
        boolean managerRole();

        String label();
    }

    private static Mutator managerPropose() {
        return new Mutator() {
            @Override
            public void act(NegotiationService service) {
                service.managerPropose(ROUND_ID, DiscountKind.PERCENT, new BigDecimal("5"));
            }

            @Override
            public boolean managerRole() {
                return true;
            }

            @Override
            public String label() {
                return "managerPropose";
            }

            @Override
            public String toString() {
                return label();
            }
        };
    }

    private static Mutator managerReject() {
        return new Mutator() {
            @Override
            public void act(NegotiationService service) {
                service.managerReject(ROUND_ID, "some reasoned explanation");
            }

            @Override
            public boolean managerRole() {
                return true;
            }

            @Override
            public String label() {
                return "managerReject";
            }

            @Override
            public String toString() {
                return label();
            }
        };
    }

    private static Mutator clientAccept() {
        return new Mutator() {
            @Override
            public void act(NegotiationService service) {
                service.clientAccept(ROUND_ID);
            }

            @Override
            public boolean managerRole() {
                return false;
            }

            @Override
            public String label() {
                return "clientAccept";
            }

            @Override
            public String toString() {
                return label();
            }
        };
    }

    private static Mutator clientDecline() {
        return new Mutator() {
            @Override
            public void act(NegotiationService service) {
                service.clientDecline(ROUND_ID);
            }

            @Override
            public boolean managerRole() {
                return false;
            }

            @Override
            public String label() {
                return "clientDecline";
            }

            @Override
            public String toString() {
                return label();
            }
        };
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a fresh {@link NegotiationService} with mocked DAOs, a mocked {@link OfferService}
     * (so {@code assertNonTerminal} does not throw — the offer is non-terminal for these
     * properties), a mocked {@link EscalationPolicy}, and a mocked {@link EntityManager}. Only the
     * collaborators a property touches are meaningfully stubbed; {@code roundDao.save} echoes its
     * argument back so an accepted rejection is returned as-is, and {@code entityManager.flush()} is
     * a no-op.
     */
    private final class Fixture {
        final OfferDao offerDao = mock(OfferDao.class);
        final OfferNegotiationRoundDao roundDao = mock(OfferNegotiationRoundDao.class);
        final OfferDiscountDao offerDiscountDao = mock(OfferDiscountDao.class);
        final OfferProjectSettingsDao offerProjectSettingsDao = mock(OfferProjectSettingsDao.class);
        final UserDao userDao = mock(UserDao.class);
        final OfferService offerService = mock(OfferService.class);
        final EscalationPolicy escalationPolicy = mock(EscalationPolicy.class);
        final org.springframework.context.ApplicationEventPublisher eventPublisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        final EntityManager entityManager = mock(EntityManager.class);

        final NegotiationService service;

        Fixture() {
            lenient().when(roundDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            lenient().doAnswer(invocation -> null).when(entityManager).flush();

            service = new NegotiationService(
                    offerDao,
                    roundDao,
                    offerDiscountDao,
                    offerProjectSettingsDao,
                    userDao,
                    offerService,
                    escalationPolicy,
                    eventPublisher,
                    entityManager);
            SecurityContextHolder.clearContext();
        }

        /** Authenticate as a MANAGER executor: numeric principal id whose user resolves to MANAGER. */
        void authenticateManager() {
            UserEntity user = new UserEntity();
            user.setId(MANAGER_USER_ID);
            RoleEntity role = new RoleEntity();
            role.setCode("MANAGER");
            user.setRole(role);
            lenient().when(userDao.findById(MANAGER_USER_ID)).thenReturn(Optional.of(user));
            authenticate(String.valueOf(MANAGER_USER_ID), "ROLE_USER");
        }

        /** Authenticate as a CLIENT: numeric principal id whose user resolves to role CLIENT. */
        void authenticateClient() {
            UserEntity user = new UserEntity();
            user.setId(CLIENT_USER_ID);
            RoleEntity role = new RoleEntity();
            role.setCode("CLIENT");
            user.setRole(role);
            lenient().when(userDao.findById(CLIENT_USER_ID)).thenReturn(Optional.of(user));
            authenticate(String.valueOf(CLIENT_USER_ID), "ROLE_USER");
        }
    }

    /** A non-terminal (SENT) offer with an empty round thread. */
    private static OfferEntity nonTerminalOffer() {
        OfferEntity offer = new OfferEntity();
        offer.setId(OFFER_ID);
        offer.setStatus(OfferStatus.SENT);
        offer.setRevision(1);
        return offer;
    }

    /** Builds a round of the given kind/status linked to the offer. */
    private static OfferNegotiationRoundEntity round(NegotiationRoundKind kind,
                                                     NegotiationRoundStatus status,
                                                     OfferEntity offer) {
        OfferNegotiationRoundEntity round = new OfferNegotiationRoundEntity();
        round.setId(ROUND_ID);
        round.setOffer(offer);
        round.setOfferRevision(1);
        round.setRoundNo(1);
        round.setInitiatorRole("CLIENT");
        round.setKind(kind);
        round.setStatus(status);
        return round;
    }

    private static void authenticate(String principalName, String authority) {
        SecurityContextHolder.clearContext();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(authority));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(principalName, "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // ---- Generators ----

    /** The four resolved (non-OPEN) round statuses (R4.7). */
    @Provide
    Arbitrary<NegotiationRoundStatus> resolvedStatuses() {
        return Arbitraries.of(
                NegotiationRoundStatus.ACCEPTED,
                NegotiationRoundStatus.DECLINED,
                NegotiationRoundStatus.REJECTED,
                NegotiationRoundStatus.SUPERSEDED);
    }

    /** The four round-acting mutators exercised by Property 15. */
    @Provide
    Arbitrary<Mutator> mutators() {
        return Arbitraries.of(
                managerPropose(),
                managerReject(),
                clientAccept(),
                clientDecline());
    }

    /**
     * Blank explanations R4.8/R6.6 must reject: {@code null}, the empty string, and all-whitespace
     * strings (spaces, tabs, newlines, and multi-whitespace combinations).
     */
    @Provide
    Arbitrary<String> blankExplanations() {
        Arbitrary<String> fixed = Arbitraries.of("", " ", "  ", "\t", "\n", "\r", "\t\n", "  \t  ", " \n \t ");
        Arbitrary<String> generatedWhitespace = Arbitraries
                .strings()
                .withChars(' ', '\t', '\n', '\r')
                .ofMinLength(1)
                .ofMaxLength(20);
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                fixed,
                generatedWhitespace);
    }

    /**
     * Non-blank explanations: a string with at least one non-whitespace character (optionally
     * surrounded by whitespace), so {@link String#isBlank()} is false.
     */
    @Provide
    Arbitrary<String> nonBlankExplanations() {
        Arbitrary<String> core = Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(64)
                .filter(s -> !s.isBlank());
        Arbitrary<String> pad = Arbitraries.of("", " ", "\t", "\n");
        return Combinators.combine(pad, core, pad).as((a, b, c) -> a + b + c);
    }
}
