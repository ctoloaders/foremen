package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.NegotiationRoundStatus;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.EscalationPolicy;
import com.foremen.service.OfferService;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Property-based test for {@link NegotiationService#clientAccept(Long)} — accepting a
 * {@link NegotiationRoundKind#MANAGER_PROPOSAL} materializes the proposal into an applied
 * {@link OfferDiscountEntity}, applies the scope override-and-cancel hierarchy to the
 * already-materialized discounts, and bumps the offer {@code revision} by exactly one (FOR-05-07,
 * design §Property 14).
 *
 * <p><b>Chosen test level.</b> {@code clientAccept} is not a pure function — it resolves the round,
 * enforces the CLIENT role from the security context, guards terminal/round state, materializes a
 * discount into the offer's collection (deleting the subsumed ones), bumps the revision, and
 * recomputes totals. It therefore cannot be exercised as a bare predicate. Following the repo's
 * established service-level property/unit pattern for {@code NegotiationService}'s sibling services
 * ({@code OfferServicePrepareOfferPropertyTest}, {@code OfferDiscountServiceTest}), the service is
 * constructed directly with <b>mocked DAOs</b> ({@link OfferDao}, {@link OfferNegotiationRoundDao},
 * {@link OfferDiscountDao}, {@link OfferProjectSettingsDao}, {@link UserDao}), a <b>mocked
 * {@link OfferService}</b> (whose {@code assertNonTerminal}/{@code recomputeTotals} collaborators
 * are no-ops here so the accept path is not blocked and totals-recompute is out of scope for this
 * property), a <b>mocked {@link EscalationPolicy}</b> pinned to "no escalation" (so the accept gate
 * always passes — the escalation gate is covered by Property 11), a mocked {@link EntityManager},
 * and a manually-driven {@link SecurityContextHolder} authenticated as a CLIENT. Runs entirely in
 * memory over 100+ iterations with no Spring context and no Testcontainers.
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 14: Accepting a proposal materializes discounts
 * respecting scope override and bumps the revision</b> — <b>Validates: Requirements 4.5, 12.4</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 14: Accepting a proposal materializes discounts respecting scope override and bumps the revision")
class NegotiationAcceptMaterializationPropertyTest {

    private static final long OFFER_ID = 700L;
    private static final long PROPOSAL_ROUND_ID = 800L;
    private static final long CLIENT_USER_ID = 22L;

    // Pre-existing materialized discount target ids used by the generators.
    private static final long CATEGORY_A = 1L;
    private static final long CATEGORY_B = 2L;
    private static final long LINE_IN_A = 10L;
    private static final long LINE_IN_B = 20L;

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------
    // Property 14: Accepting a MANAGER_PROPOSAL materializes exactly one applied discount matching
    // the proposal's scope/kind/value with sourceRound == the proposal, respects scope
    // override-and-cancel over the already-materialized discounts (a GLOBAL acceptance removes all
    // CATEGORY/LINE discounts; a CATEGORY acceptance removes the same-target LINE discounts), and
    // bumps the offer revision by exactly one.
    // Validates: Requirements 4.5, 12.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 14: Accepting a proposal materializes discounts respecting scope override and bumps the revision")
    void acceptMaterializesDiscountRespectingScopeOverrideAndBumpsRevision(
            @ForAll("proposals") ProposalSpec spec,
            @ForAll("startingRevisions") int startingRevision) {

        Fixture f = new Fixture();

        // The offer starts with a fixed set of already-materialized discounts spanning every scope.
        OfferEntity offer = offerWithPreExistingDiscounts(startingRevision);
        List<OfferDiscountEntity> preExisting = new ArrayList<>(offer.getDiscounts());

        // The OPEN manager proposal the client is about to accept.
        OfferNegotiationRoundEntity proposal =
                proposalRound(offer, spec.scope(), spec.targetId(), spec.kind(), spec.value());
        offer.getNegotiationRounds().add(proposal);

        when(f.offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));
        when(f.roundDao.findById(PROPOSAL_ROUND_ID)).thenReturn(Optional.of(proposal));

        f.authenticateClient();

        OfferNegotiationRoundEntity accepted = f.service.clientAccept(PROPOSAL_ROUND_ID);

        // The accepted round is the same proposal, now marked ACCEPTED.
        assertThat(accepted).isSameAs(proposal);
        assertThat(proposal.getStatus()).isEqualTo(NegotiationRoundStatus.ACCEPTED);

        // Revision bumped by EXACTLY one (R4.5/R12.4).
        assertThat(offer.getRevision()).isEqualTo(startingRevision + 1);

        // The subsumed pre-existing discounts (override-and-cancel) are removed; the rest survive.
        List<OfferDiscountEntity> expectedSurvivors = preExisting.stream()
                .filter(d -> !subsumed(spec.scope(), spec.targetId(), d.getScope(), d.getTargetId()))
                .toList();

        // Exactly one NEW discount was materialized from the proposal.
        List<OfferDiscountEntity> materialized = offer.getDiscounts().stream()
                .filter(d -> d.getSourceRound() == proposal)
                .toList();
        assertThat(materialized).hasSize(1);
        OfferDiscountEntity newDiscount = materialized.get(0);

        // The materialized discount matches the proposal's scope/kind/value and back-references it.
        assertThat(newDiscount.getScope()).isEqualTo(spec.scope());
        assertThat(newDiscount.getTargetId()).isEqualTo(spec.targetId());
        assertThat(newDiscount.getKind()).isEqualTo(spec.kind());
        assertThat(newDiscount.getValue()).isEqualByComparingTo(spec.value());
        assertThat(newDiscount.getOffer()).isSameAs(offer);

        // The resulting applied set is exactly the surviving pre-existing discounts plus the new one.
        assertThat(offer.getDiscounts())
                .containsExactlyInAnyOrderElementsOf(concat(expectedSurvivors, newDiscount));

        // No discount subsumed by this acceptance's scope remains (override-and-cancel is complete).
        assertThat(offer.getDiscounts())
                .filteredOn(d -> d != newDiscount)
                .noneMatch(d -> subsumed(spec.scope(), spec.targetId(), d.getScope(), d.getTargetId()));
    }

    // ---- override-and-cancel oracle (mirrors NegotiationService#subsumes semantics) ----

    /**
     * Whether an acceptance at {@code broaderScope}/{@code broaderTarget} subsumes an
     * already-materialized discount at {@code narrowerScope}/{@code narrowerTarget}: a {@code GLOBAL}
     * acceptance subsumes every {@code CATEGORY} and {@code LINE} discount; a {@code CATEGORY}
     * acceptance subsumes the {@code LINE} discounts whose target equals the category target; a
     * {@code LINE} acceptance subsumes nothing.
     */
    private static boolean subsumed(DiscountScope broaderScope, Long broaderTarget,
                                    DiscountScope narrowerScope, Long narrowerTarget) {
        if (narrowerScope == null) {
            return false;
        }
        return switch (broaderScope) {
            case GLOBAL -> narrowerScope == DiscountScope.CATEGORY || narrowerScope == DiscountScope.LINE;
            case CATEGORY -> narrowerScope == DiscountScope.LINE
                    && broaderTarget != null && broaderTarget.equals(narrowerTarget);
            case LINE -> false;
        };
    }

    private static List<OfferDiscountEntity> concat(List<OfferDiscountEntity> base, OfferDiscountEntity extra) {
        List<OfferDiscountEntity> all = new ArrayList<>(base);
        all.add(extra);
        return all;
    }

    // ---- fixtures ----

    /**
     * A DRAFT (non-terminal) offer carrying a fixed, scope-spanning set of already-materialized
     * discounts: one GLOBAL, two CATEGORY (A, B), and two LINE (targeting A and B). This lets each
     * generated acceptance exercise its override-and-cancel branch against every narrower scope.
     */
    private OfferEntity offerWithPreExistingDiscounts(int startingRevision) {
        OfferEntity offer = new OfferEntity();
        offer.setId(OFFER_ID);
        offer.setRevision(startingRevision);
        offer.setDiscounts(new ArrayList<>(List.of(
                discount(1000L, DiscountScope.GLOBAL, null),
                discount(1001L, DiscountScope.CATEGORY, CATEGORY_A),
                discount(1002L, DiscountScope.CATEGORY, CATEGORY_B),
                discount(1003L, DiscountScope.LINE, LINE_IN_A),
                discount(1004L, DiscountScope.LINE, LINE_IN_B))));
        offer.setNegotiationRounds(new ArrayList<>());
        return offer;
    }

    private static OfferDiscountEntity discount(long id, DiscountScope scope, Long targetId) {
        OfferDiscountEntity discount = new OfferDiscountEntity();
        discount.setId(id);
        discount.setScope(scope);
        discount.setTargetId(targetId);
        discount.setKind(DiscountKind.ABSOLUTE);
        discount.setValue(new BigDecimal("5"));
        return discount;
    }

    private static OfferNegotiationRoundEntity proposalRound(OfferEntity offer,
                                                             DiscountScope scope,
                                                             Long targetId,
                                                             DiscountKind kind,
                                                             BigDecimal value) {
        OfferNegotiationRoundEntity proposal = new OfferNegotiationRoundEntity();
        proposal.setId(PROPOSAL_ROUND_ID);
        proposal.setOffer(offer);
        proposal.setKind(NegotiationRoundKind.MANAGER_PROPOSAL);
        proposal.setScope(scope);
        proposal.setTargetId(targetId);
        proposal.setValueKind(kind);
        proposal.setValue(value);
        proposal.setStatus(NegotiationRoundStatus.OPEN);
        proposal.setOfferRevision(offer.getRevision());
        proposal.setRoundNo(1);
        proposal.setInitiatorRole("MANAGER");
        return proposal;
    }

    /**
     * Bundles a fresh {@link NegotiationService} with mocked DAOs, a mocked {@link OfferService}
     * (assertNonTerminal / recomputeTotals no-ops), a mocked {@link EscalationPolicy} pinned to "no
     * escalation", and a no-op {@link EntityManager}. {@code offerDiscountDao.save} echoes its
     * argument back (so the materialized discount is the same instance the service linked into the
     * collection); {@code offerDiscountDao.delete} is a no-op (the service removes the entity from
     * the offer's collection itself).
     */
    private static final class Fixture {
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
            when(offerDiscountDao.save(any(OfferDiscountEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().doNothing().when(offerDiscountDao).delete(any(OfferDiscountEntity.class));
            // assertNonTerminal / recomputeTotals are no-ops: the accept path proceeds and totals
            // recompute is out of scope for this property (asserted elsewhere).
            lenient().doNothing().when(offerService).assertNonTerminal(any(OfferEntity.class));
            lenient().doNothing().when(offerService).recomputeTotals(any(OfferEntity.class));
            // No escalation for any proposal so clientAccept's gate always passes (Property 11 covers
            // the escalation gate itself).
            lenient().when(escalationPolicy.requiresAdminApproval(any(), any(), any(), any()))
                    .thenReturn(false);
            doAnswer(inv -> null).when(entityManager).flush();

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
        }

        /** Authenticate as a CLIENT: numeric principal id whose user resolves to role CLIENT. */
        void authenticateClient() {
            UserEntity user = new UserEntity();
            user.setId(CLIENT_USER_ID);
            RoleEntity role = new RoleEntity();
            role.setCode("CLIENT");
            user.setRole(role);
            lenient().when(userDao.findById(CLIENT_USER_ID)).thenReturn(Optional.of(user));
            SecurityContextHolder.clearContext();
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(String.valueOf(CLIENT_USER_ID), "n/a", authorities);
            SecurityContextHolder.getContext().setAuthentication(token);
        }
    }

    // ---- generators ----

    /** The accepted manager proposal's scope/target/kind/value. */
    record ProposalSpec(DiscountScope scope, Long targetId, DiscountKind kind, BigDecimal value) {}

    /**
     * Generates the accepted proposal across all three scopes, exercising each override-and-cancel
     * branch: a {@code GLOBAL} proposal (null target), a {@code CATEGORY} proposal targeting category
     * A or B (so it subsumes the same-target LINE discount), and a {@code LINE} proposal (subsumes
     * nothing). Kind is PERCENT or ABSOLUTE; value is a non-negative figure within valid bounds.
     */
    @Provide
    Arbitrary<ProposalSpec> proposals() {
        Arbitrary<DiscountKind> kinds = Arbitraries.of(DiscountKind.PERCENT, DiscountKind.ABSOLUTE);
        Arbitrary<BigDecimal> values = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100"))
                .ofScale(2);

        Arbitrary<ProposalSpec> global = Combinators.combine(kinds, values)
                .as((k, v) -> new ProposalSpec(DiscountScope.GLOBAL, null, k, v));

        Arbitrary<ProposalSpec> category = Combinators.combine(
                        Arbitraries.of(CATEGORY_A, CATEGORY_B), kinds, values)
                .as((t, k, v) -> new ProposalSpec(DiscountScope.CATEGORY, t, k, v));

        Arbitrary<ProposalSpec> line = Combinators.combine(
                        Arbitraries.of(LINE_IN_A, LINE_IN_B, 999L), kinds, values)
                .as((t, k, v) -> new ProposalSpec(DiscountScope.LINE, t, k, v));

        return Arbitraries.oneOf(global, category, line);
    }

    /** Starting offer revisions across the valid positive range. */
    @Provide
    Arbitrary<Integer> startingRevisions() {
        return Arbitraries.integers().between(1, 10_000);
    }
}
