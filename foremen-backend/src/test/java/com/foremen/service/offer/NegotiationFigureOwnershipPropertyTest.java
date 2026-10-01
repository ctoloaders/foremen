package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.config.offer.OfferEscalationProperties;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferDiscountDao;
import com.foremen.dao.OfferNegotiationRoundDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.OfferProjectSettingsDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.EscalationPolicy;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;

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
 * Property-based test for the offer negotiation <b>figure-ownership</b> invariant (FOR-05-07, design
 * §Property 13). Across every round-creating negotiation action, a persisted
 * {@link OfferNegotiationRoundEntity} carries a discount figure ({@code value} + {@code valueKind})
 * <b>if and only if</b> its {@code kind} is {@link NegotiationRoundKind#MANAGER_PROPOSAL}: a client
 * {@code DISCOUNT_REQUEST} (R4.1), a {@code MANAGER_REJECT}, and the {@code CLIENT_ACCEPT} /
 * {@code CLIENT_DECLINE} rounds carry <b>no</b> figure, and only a {@code MANAGER_PROPOSAL} carries
 * one (R4.3 / R10.18). Per-material ({@code LINE}-scoped) requests reuse the exact same machinery
 * (R12.1), so the invariant is asserted uniformly across all three scopes.
 *
 * <p><b>Chosen test level.</b> The invariant is a property of the rounds the
 * {@link NegotiationService} <em>creates</em>, so the property is expressed over the real
 * {@code NegotiationService} rather than a pure predicate. Following the repo's established
 * service-level property-test pattern (e.g. {@code OfferServicePrepareOfferPropertyTest}), the
 * service is constructed with <b>mocked DAOs</b> ({@link OfferDao}, {@link OfferNegotiationRoundDao},
 * {@link OfferDiscountDao}, {@link OfferProjectSettingsDao}, {@link UserDao}, {@link EntityManager}),
 * the <b>real pure collaborators</b> ({@link EscalationPolicy} configured with no caps so escalation
 * never blocks a proposal, and a <b>real</b> {@link OfferService} built the same way for
 * {@code transitionOfferStatus}/{@code assertNonTerminal}/{@code recomputeTotals}). {@code roundDao.save}
 * captures every persisted round; each captured round is checked against the figure-ownership
 * invariant. The acting role is set on the {@link SecurityContextHolder} per action (CLIENT for the
 * request/accept/decline mutators, MANAGER for propose/reject). This runs entirely in memory over
 * 100+ iterations with no Spring context and no Testcontainers.
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 13: Only a manager proposal carries a discount
 * figure</b> — <b>Validates: Requirements 4.1, 4.3, 10.18, 12.1</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 13: Only a manager proposal carries a discount figure")
class NegotiationFigureOwnershipPropertyTest {

    private static final long CLIENT_USER_ID = 11L;
    private static final long MANAGER_USER_ID = 22L;

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------
    // Property 13: For every round the NegotiationService creates while replaying an arbitrary
    // negotiation script, the round carries a discount figure (value + valueKind) IFF its kind is
    // MANAGER_PROPOSAL. A DISCOUNT_REQUEST, MANAGER_REJECT, and CLIENT_DECLINE carry no figure.
    // Validates: Requirements 4.1, 4.3, 10.18, 12.1
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 13: Only a manager proposal carries a discount figure")
    void onlyManagerProposalCarriesAFigure(@ForAll("scenarios") Scenario scenario) {
        Fixture f = new Fixture();

        // 1. Client opens a discount request (no figure) on a SENT offer -> CHANGES_REQUESTED.
        f.asClient();
        OfferNegotiationRoundEntity request = f.negotiationService.openDiscountRequest(
                f.offer.getId(), scenario.scope, scenario.targetId, scenario.justification, scenario.clientComment);

        // 2. Manager either proposes a figure (-> COUNTERED) or rejects with an explanation.
        f.asManager();
        if (scenario.managerProposes) {
            OfferNegotiationRoundEntity proposal =
                    f.negotiationService.managerPropose(request.getId(), scenario.kind, scenario.value);

            // 3. On a proposal, the client accepts or declines.
            f.asClient();
            if (scenario.clientAccepts) {
                f.negotiationService.clientAccept(proposal.getId());
            } else {
                f.negotiationService.clientDecline(proposal.getId());
            }
        } else {
            f.negotiationService.managerReject(request.getId(), scenario.explanation);
        }

        // The invariant must hold for EVERY round the service created during the script.
        assertThat(f.savedRounds).isNotEmpty();
        for (OfferNegotiationRoundEntity round : f.savedRounds) {
            boolean isProposal = round.getKind() == NegotiationRoundKind.MANAGER_PROPOSAL;
            boolean carriesFigure = round.getValue() != null && round.getValueKind() != null;
            assertThat(carriesFigure)
                    .as("round kind %s must carry a figure IFF it is a MANAGER_PROPOSAL", round.getKind())
                    .isEqualTo(isProposal);

            if (isProposal) {
                // A proposal carries BOTH parts of the figure.
                assertThat(round.getValue()).isNotNull();
                assertThat(round.getValueKind()).isNotNull();
            } else {
                // Every non-proposal round (DISCOUNT_REQUEST / MANAGER_REJECT / CLIENT_ACCEPT /
                // CLIENT_DECLINE) carries NEITHER part of the figure (R4.1 / R10.18).
                assertThat(round.getValue()).isNull();
                assertThat(round.getValueKind()).isNull();
            }
        }

        // Sanity: the client's DISCOUNT_REQUEST is present and figure-free (R4.1 / R12.1).
        OfferNegotiationRoundEntity persistedRequest = f.savedRounds.stream()
                .filter(r -> r.getKind() == NegotiationRoundKind.DISCOUNT_REQUEST)
                .findFirst().orElseThrow();
        assertThat(persistedRequest.getValue()).isNull();
        assertThat(persistedRequest.getValueKind()).isNull();
    }

    // ---- Scenario record + generator ----

    /** One arbitrary negotiation script driving the request -> propose/reject -> accept/decline path. */
    record Scenario(DiscountScope scope,
                    Long targetId,
                    String justification,
                    String clientComment,
                    boolean managerProposes,
                    DiscountKind kind,
                    BigDecimal value,
                    boolean clientAccepts,
                    String explanation) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<DiscountScope> scopes = Arbitraries.of(DiscountScope.values());
        Arbitrary<Long> targets = Arbitraries.longs().between(1L, 5_000L);
        Arbitrary<String> justifications =
                Arbitraries.strings().ofMinLength(1).ofMaxLength(120).filter(s -> !s.isBlank());
        Arbitrary<Boolean> proposeFlag = Arbitraries.of(true, false);
        Arbitrary<DiscountKind> kinds = Arbitraries.of(DiscountKind.values());
        // Non-negative, within-bound values so the manager path never trips validation/escalation.
        Arbitrary<BigDecimal> values =
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("50")).ofScale(2);
        Arbitrary<Boolean> acceptFlag = Arbitraries.of(true, false);
        Arbitrary<String> explanations =
                Arbitraries.strings().ofMinLength(1).ofMaxLength(120).filter(s -> !s.isBlank());

        // Combinators.combine supports at most 8 arbitraries; clientComment (a nullable free-text
        // field that never affects the figure invariant) is derived from the justification instead.
        return Combinators.combine(scopes, targets, justifications, proposeFlag,
                        kinds, values, acceptFlag, explanations)
                .as((scope, target, just, propose, kind, value, accept, expl) ->
                        new Scenario(scope,
                                scope == DiscountScope.GLOBAL ? null : target,
                                just,
                                scope == DiscountScope.LINE ? just + " (line)" : null,
                                propose, kind, value, accept, expl));
    }

    // ---- Fixture ----

    /**
     * Bundles a fresh {@link NegotiationService} over mocked DAOs, a real {@link EscalationPolicy}
     * (no caps -> escalation never blocks), a real {@link OfferService} (for the status transitions
     * and totals recompute), and a single {@code SENT} offer. {@code roundDao.save} assigns ids and
     * records every persisted round into {@link #savedRounds}. Role is toggled via {@link #asClient()}
     * / {@link #asManager()}.
     */
    private static final class Fixture {
        final OfferDao offerDao = mock(OfferDao.class);
        final OfferNegotiationRoundDao roundDao = mock(OfferNegotiationRoundDao.class);
        final OfferDiscountDao offerDiscountDao = mock(OfferDiscountDao.class);
        final OfferProjectSettingsDao offerProjectSettingsDao = mock(OfferProjectSettingsDao.class);
        final UserDao userDao = mock(UserDao.class);
        final EntityManager entityManager = mock(EntityManager.class);

        // OfferService mocked deps (only the ones prepareOffer/transition/recompute touch matter).
        final EstimateDao estimateDao = mock(EstimateDao.class);
        final com.foremen.dao.EstimateLineRoomMaterialDao estimateLineRoomMaterialDao =
                mock(com.foremen.dao.EstimateLineRoomMaterialDao.class);
        final OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        final ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        final DraftGateGuard draftGateGuard = mock(DraftGateGuard.class);
        final EstimateAssignmentService estimateAssignmentService = mock(EstimateAssignmentService.class);
        final org.springframework.context.ApplicationEventPublisher eventPublisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        final ClientOfferReadModelAssembler clientOfferReadModelAssembler =
                mock(ClientOfferReadModelAssembler.class);

        // Real pure collaborators.
        final OfferStatusMachine offerStatusMachine = new OfferStatusMachine();
        final OfferTotalsCalculator offerTotalsCalculator = new OfferTotalsCalculator();
        final DiscountResolver discountResolver = new DiscountResolver();
        // No caps configured -> requiresAdminApproval is always false; escalation never blocks.
        final EscalationPolicy escalationPolicy =
                new EscalationPolicy(new OfferEscalationProperties(null, null));

        final OfferService offerService;
        final NegotiationService negotiationService;

        final OfferEntity offer;
        final List<OfferNegotiationRoundEntity> savedRounds = new ArrayList<>();
        private final AtomicLong roundIdSeq = new AtomicLong(1_000L);

        Fixture() {
            ProjectEntity project = new ProjectEntity();
            project.setId(7L);

            EstimateEntity estimate = new EstimateEntity();
            estimate.setId(70L);
            estimate.setProject(project);
            estimate.setLines(List.of());

            offer = new OfferEntity();
            offer.setId(700L);
            offer.setProject(project);
            offer.setEstimate(estimate);
            offer.setStatus(OfferStatus.SENT); // negotiable, non-terminal
            offer.setRevision(1);
            offer.setDiscounts(new ArrayList<>());
            offer.setNegotiationRounds(new ArrayList<>());

            when(offerDao.findById(offer.getId())).thenReturn(Optional.of(offer));
            when(offerDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // roundDao.save assigns an id (if unset), records the round, and echoes it back so
            // resolveRound(roundId) can find it later in the script.
            when(roundDao.save(any())).thenAnswer(inv -> {
                OfferNegotiationRoundEntity r = inv.getArgument(0);
                if (r.getId() == null) {
                    r.setId(roundIdSeq.getAndIncrement());
                }
                savedRounds.add(r);
                return r;
            });
            when(roundDao.findById(anyLong())).thenAnswer(inv -> {
                Long id = inv.getArgument(0);
                return savedRounds.stream().filter(r -> id.equals(r.getId())).findFirst();
            });
            when(offerDiscountDao.save(any())).thenAnswer(inv -> {
                OfferDiscountEntity d = inv.getArgument(0);
                if (d.getId() == null) {
                    d.setId(roundIdSeq.getAndIncrement());
                }
                return d;
            });
            when(offerProjectSettingsDao.findByProjectId(anyLong())).thenReturn(Optional.empty());

            offerService = new OfferService(
                    offerDao,
                    estimateDao,
                    estimateLineRoomMaterialDao,
                    offerPackageDao,
                    userDao,
                    /* offerServiceMapper */ null,
                    projectAccessCache,
                    /* auditLogDao */ null,
                    entityManager,
                    offerStatusMachine,
                    offerTotalsCalculator,
                    discountResolver,
                    draftGateGuard,
                    estimateAssignmentService,
                    eventPublisher,
                    clientOfferReadModelAssembler);

            negotiationService = new NegotiationService(
                    offerDao,
                    roundDao,
                    offerDiscountDao,
                    offerProjectSettingsDao,
                    userDao,
                    offerService,
                    escalationPolicy,
                    eventPublisher,
                    entityManager);

            // Users the security context principal resolves to.
            when(userDao.findById(CLIENT_USER_ID)).thenReturn(Optional.of(user(CLIENT_USER_ID, "CLIENT")));
            when(userDao.findById(MANAGER_USER_ID)).thenReturn(Optional.of(user(MANAGER_USER_ID, "MANAGER")));
        }

        void asClient() {
            authenticateAs(CLIENT_USER_ID);
        }

        void asManager() {
            authenticateAs(MANAGER_USER_ID);
        }

        private static void authenticateAs(long userId) {
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    String.valueOf(userId), "n/a",
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        private static UserEntity user(long id, String roleCode) {
            RoleEntity role = new RoleEntity();
            role.setCode(roleCode);
            UserEntity u = new UserEntity();
            u.setId(id);
            u.setRole(role);
            return u;
        }
    }
}
