package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferDiscountDao;
import com.foremen.dao.OfferNegotiationRoundDao;
import com.foremen.dao.OfferProjectSettingsDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.NegotiationRoundStatus;
import com.foremen.dao.model.OfferAction;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.dao.model.OfferProjectSettingsEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EscalationPolicy;
import com.foremen.service.OfferService;

import jakarta.persistence.EntityManager;

/**
 * Two-sided offer negotiation thread mutator service (FOR-05-07, Requirements 4.1–4.9, 6.1, 6.3,
 * 6.5, 6.6, 12.1–12.5; design §NegotiationService).
 *
 * <p>The negotiation is a threaded, never-deleted history of {@link OfferNegotiationRoundEntity}
 * rows on a single non-terminal {@link OfferEntity}. The <b>figure is owned by the MANAGER</b>: a
 * client {@link NegotiationRoundKind#DISCOUNT_REQUEST} carries only scope + target + justification
 * and NO {@code value}/{@code valueKind} (R4.1); the manager decides the number via a
 * {@link NegotiationRoundKind#MANAGER_PROPOSAL} (kind + value) or refuses via a
 * {@link NegotiationRoundKind#MANAGER_REJECT} with a mandatory reasoned explanation (R4.8/R6.6). The
 * client then closes with {@link NegotiationRoundKind#CLIENT_ACCEPT} (materializes the proposal into
 * applied discounts and bumps the revision, R4.5) or {@link NegotiationRoundKind#CLIENT_DECLINE}.
 *
 * <h2>Cross-cutting guards every mutator applies</h2>
 * <ul>
 *   <li><b>Role enforcement.</b> {@code open*}/{@code client*} mutators are CLIENT-only and the
 *       {@code manager*} mutators are MANAGER/ADMIN-only, enforced server-side from the security
 *       context the same way {@link OfferService}/{@link OfferDiscountService} resolve the acting
 *       role. A wrong-role caller is rejected {@code 403 error.offer.negotiation.forbidden}.</li>
 *   <li><b>Terminal-offer guard.</b> Every mutator first calls
 *       {@link OfferService#assertNonTerminal(OfferEntity)} so no round is opened or acted on for a
 *       terminal offer (R3.9 → {@code 409 error.offer.illegal.transition}).</li>
 *   <li><b>Round-state guard.</b> A mutator acting on an existing round requires it to be
 *       {@link NegotiationRoundStatus#OPEN}; a resolved round (ACCEPTED/DECLINED/REJECTED/SUPERSEDED)
 *       is rejected {@code 409 error.offer.round.resolved} (R4.7).</li>
 *   <li><b>Revision binding.</b> Every new round is bound to the offer's current
 *       {@code Offer_Revision} (R4.6).</li>
 *   <li><b>Offer status transitions.</b> The two status-affecting mutators route the offer status
 *       change through {@link OfferService} (which delegates to {@link OfferStatusMachine}):
 *       {@code openDiscountRequest} drives {@code SENT}/{@code COUNTERED → CHANGES_REQUESTED} via
 *       {@link OfferAction#REQUEST_CHANGES} and {@code managerPropose} drives
 *       {@code CHANGES_REQUESTED → COUNTERED} via {@link OfferAction#PROPOSE}.</li>
 * </ul>
 *
 * <h2>Scope override-and-cancel (R4.9)</h2>
 * When a broader-scope proposition is created, the still-{@code OPEN} narrower-scope propositions it
 * subsumes are marked {@link NegotiationRoundStatus#SUPERSEDED}: a {@code CATEGORY} proposition
 * supersedes the {@code LINE} propositions within its work-type group, and a {@code GLOBAL}
 * proposition supersedes ALL {@code LINE} and {@code CATEGORY} propositions. Per-material
 * {@code LINE}-scoped requests reuse this exact machinery (R12.1/R12.2).
 *
 * <h2>Escalation (R6.3/R6.5, Property 11)</h2>
 * {@code managerPropose} consults {@link EscalationPolicy}: when the proposed value exceeds the
 * effective {@code Escalation_Threshold} (per-project override, else GLOBAL config) the proposal
 * requires ADMIN approval. An ADMIN caller satisfies the gate directly (the proposal is recorded
 * {@code adminApproved = true}); a plain MANAGER cannot issue an over-threshold proposal
 * ({@code 409 error.offer.escalation.required}). {@code clientAccept} re-checks the gate before
 * materializing, so an over-threshold proposal is never accepted into an applied discount without
 * ADMIN approval.
 */
@Service
public class NegotiationService {

    /** 403 when a negotiation mutator is called by the wrong role (CLIENT vs MANAGER/ADMIN). */
    static final String FORBIDDEN_MESSAGE = "error.offer.negotiation.forbidden";

    /** 409 when acting on a round that is not {@code OPEN} (already resolved) (R4.7). */
    static final String ROUND_RESOLVED_MESSAGE = "error.offer.round.resolved";

    /** 400 when a {@code MANAGER_REJECT} carries a missing/blank explanation (R4.8/R6.6). */
    static final String REJECT_EXPLANATION_REQUIRED_MESSAGE =
            "error.offer.reject.explanation.required";

    /** 409 when an over-threshold {@code MANAGER_PROPOSAL} lacks ADMIN approval (R6.5). */
    static final String ESCALATION_REQUIRED_MESSAGE = "error.offer.escalation.required";

    /** 404 when the referenced offer or round cannot be resolved. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    private static final String CLIENT_ROLE = "CLIENT";
    private static final String ADMIN_ROLE = "ADMIN";

    private final OfferDao offerDao;
    private final OfferNegotiationRoundDao roundDao;
    private final OfferDiscountDao offerDiscountDao;
    private final OfferProjectSettingsDao offerProjectSettingsDao;
    private final UserDao userDao;
    private final OfferService offerService;
    private final EscalationPolicy escalationPolicy;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public NegotiationService(OfferDao offerDao,
                              OfferNegotiationRoundDao roundDao,
                              OfferDiscountDao offerDiscountDao,
                              OfferProjectSettingsDao offerProjectSettingsDao,
                              UserDao userDao,
                              OfferService offerService,
                              EscalationPolicy escalationPolicy,
                              ApplicationEventPublisher eventPublisher,
                              EntityManager entityManager) {
        this.offerDao = offerDao;
        this.roundDao = roundDao;
        this.offerDiscountDao = offerDiscountDao;
        this.offerProjectSettingsDao = offerProjectSettingsDao;
        this.userDao = userDao;
        this.offerService = offerService;
        this.escalationPolicy = escalationPolicy;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    // --- CLIENT: open a discount request (R4.1, R4.2, R12.1, R12.2) ---

    /**
     * Opens a client discount request: creates a {@link NegotiationRoundKind#DISCOUNT_REQUEST} round
     * carrying only {@code scope} + {@code targetId} + free-text {@code justification} (and an
     * optional {@code clientComment} on a {@code LINE}-scoped request, R4.2) and <b>no</b>
     * {@code value}/{@code valueKind} — the figure is the manager's to decide (R4.1). Drives the
     * offer {@code SENT}/{@code COUNTERED → CHANGES_REQUESTED} (R3.3). A per-material request is a
     * {@code LINE}-scoped invocation of this same method (R12.1/R12.2).
     *
     * @param offerId       the target offer
     * @param scope         the requested discount scope ({@code GLOBAL}/{@code CATEGORY}/{@code LINE})
     * @param targetId      null for {@code GLOBAL}, category id for {@code CATEGORY}, line id for {@code LINE}
     * @param justification free-text justification for the request
     * @param clientComment optional client comment (LINE scope, R4.2); may be {@code null}
     * @return the persisted {@code DISCOUNT_REQUEST} round
     * @throws ForemenApiException 403 non-client caller; 404 offer missing; 409 terminal offer or
     *                             illegal status transition
     */
    @Transactional
    public OfferNegotiationRoundEntity openDiscountRequest(Long offerId,
                                                           DiscountScope scope,
                                                           Long targetId,
                                                           String justification,
                                                           String clientComment) {
        OfferEntity offer = resolveOffer(offerId);
        assertClient();
        offerService.assertNonTerminal(offer);

        // R3.3: SENT/COUNTERED -> CHANGES_REQUESTED (validated by the status machine).
        offerService.transitionOfferStatus(offer, OfferAction.REQUEST_CHANGES);

        OfferNegotiationRoundEntity round = newRound(offer, NegotiationRoundKind.DISCOUNT_REQUEST, CLIENT_ROLE);
        round.setScope(scope);
        round.setTargetId(targetId);
        round.setJustification(justification);
        round.setClientComment(clientComment);
        round.setStatus(NegotiationRoundStatus.OPEN);

        // R4.9: a broader-scope request supersedes the narrower OPEN propositions it subsumes.
        supersedeNarrowerOpenRounds(offer, scope, targetId, round);

        persistRound(offer, round);
        offerDao.save(offer);
        entityManager.flush();

        // R14.1: best-effort notify the project MANAGER of the new client request. Published as an
        // after-commit event so a notification failure can never roll back this transition (R14.5).
        eventPublisher.publishEvent(new OfferNegotiationNotificationEvent(
                OfferNegotiationNotificationEvent.Trigger.DISCOUNT_REQUEST_TO_MANAGER,
                offer.getId(),
                projectId(offer),
                null));
        return round;
    }

    // --- MANAGER: propose a figure (R4.3, R6.3, R6.5) ---

    /**
     * Records a manager proposal answering an open client request (or a client's decline): a
     * {@link NegotiationRoundKind#MANAGER_PROPOSAL} carrying the proposed {@code kind} + {@code value}
     * and inheriting the request's scope/target (R4.3). Drives the offer
     * {@code CHANGES_REQUESTED → COUNTERED} (R3.4).
     *
     * <p>The proposal is gated by {@link EscalationPolicy}: when its value exceeds the effective
     * {@code Escalation_Threshold} it requires ADMIN approval (R6.3/R6.5). An ADMIN caller satisfies
     * the gate (the round is recorded {@code adminApproved = true}); a plain MANAGER issuing an
     * over-threshold value is rejected {@code 409 error.offer.escalation.required} (R6.5).
     *
     * @param roundId the open request round being answered
     * @param kind    the proposed discount kind ({@code PERCENT}/{@code ABSOLUTE})
     * @param value   the proposed figure
     * @return the persisted {@code MANAGER_PROPOSAL} round
     * @throws ForemenApiException 403 non-manager caller; 404 offer/round missing; 409 terminal offer,
     *                             resolved round, illegal transition, or un-approved escalation
     */
    @Transactional
    public OfferNegotiationRoundEntity managerPropose(Long roundId, DiscountKind kind, BigDecimal value) {
        OfferNegotiationRoundEntity request = resolveRound(roundId);
        OfferEntity offer = request.getOffer();
        boolean admin = assertManager();
        offerService.assertNonTerminal(offer);
        assertRoundOpen(request);

        // Escalation gate (R6.3/R6.5): an over-threshold value needs ADMIN authority.
        BigDecimal scopeBase = scopeBase(offer, request.getScope(), request.getTargetId());
        boolean escalates = escalationPolicy.requiresAdminApproval(
                kind, value, scopeBase, resolveProjectSettings(offer));
        if (escalates && !admin) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ESCALATION_REQUIRED_MESSAGE);
        }

        // R3.4: CHANGES_REQUESTED -> COUNTERED (validated by the status machine).
        offerService.transitionOfferStatus(offer, OfferAction.PROPOSE);

        // The client request is answered — it is no longer open.
        request.setStatus(NegotiationRoundStatus.ACCEPTED);

        OfferNegotiationRoundEntity proposal =
                newRound(offer, NegotiationRoundKind.MANAGER_PROPOSAL, resolveActorRole());
        proposal.setScope(request.getScope());
        proposal.setTargetId(request.getTargetId());
        // The figure lives ONLY on a MANAGER_PROPOSAL (R4.3 / R10.18).
        proposal.setValueKind(kind);
        proposal.setValue(value);
        proposal.setStatus(NegotiationRoundStatus.OPEN);
        // Escalating proposals are recorded approved only when an ADMIN issued them.
        proposal.setAdminApproved(escalates ? admin : true);

        // R4.9: a broader-scope proposal supersedes the narrower OPEN propositions it subsumes.
        supersedeNarrowerOpenRounds(offer, request.getScope(), request.getTargetId(), proposal);

        persistRound(offer, proposal);
        offerDao.save(offer);
        entityManager.flush();

        // R14.2: best-effort notify the requesting CLIENT (the createdBy of the answered request
        // round) of the manager's proposal. After-commit so a failure never rolls back (R14.5).
        eventPublisher.publishEvent(new OfferNegotiationNotificationEvent(
                OfferNegotiationNotificationEvent.Trigger.MANAGER_PROPOSAL_TO_CLIENT,
                offer.getId(),
                projectId(offer),
                requestingClientUserId(request)));
        return proposal;
    }

    // --- MANAGER: reject a request (R4.8, R6.6) ---

    /**
     * Records a manager rejection of an open client request (or of a client's decline): a
     * {@link NegotiationRoundKind#MANAGER_REJECT} carrying a mandatory non-blank {@code explanation}
     * (R4.8/R6.6). A missing/blank (all-whitespace) explanation is rejected server-side and no
     * rejection is recorded ({@code 400 error.offer.reject.explanation.required}).
     *
     * <p>A rejection carries no figure and does not itself transition the offer status (the offer
     * stays in its negotiable state so the manager may still propose or the client may withdraw).
     *
     * @param roundId     the open request round being refused
     * @param explanation the mandatory reasoned explanation (must be non-blank)
     * @return the persisted {@code MANAGER_REJECT} round
     * @throws ForemenApiException 403 non-manager caller; 400 blank explanation; 404 offer/round
     *                             missing; 409 terminal offer or resolved round
     */
    @Transactional
    public OfferNegotiationRoundEntity managerReject(Long roundId, String explanation) {
        OfferNegotiationRoundEntity request = resolveRound(roundId);
        OfferEntity offer = request.getOffer();
        assertManager();
        offerService.assertNonTerminal(offer);
        assertRoundOpen(request);

        // R4.8/R6.6: a reasoned explanation is mandatory — reject a missing/blank one before recording.
        if (explanation == null || explanation.isBlank()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, REJECT_EXPLANATION_REQUIRED_MESSAGE);
        }

        // The client request is resolved as rejected.
        request.setStatus(NegotiationRoundStatus.REJECTED);

        OfferNegotiationRoundEntity rejection =
                newRound(offer, NegotiationRoundKind.MANAGER_REJECT, resolveActorRole());
        rejection.setScope(request.getScope());
        rejection.setTargetId(request.getTargetId());
        rejection.setExplanation(explanation);
        rejection.setStatus(NegotiationRoundStatus.REJECTED);

        persistRound(offer, rejection);
        offerDao.save(offer);
        entityManager.flush();

        // R14.3: best-effort notify the requesting CLIENT (the createdBy of the answered request
        // round) of the manager's rejection. After-commit so a failure never rolls back (R14.5).
        eventPublisher.publishEvent(new OfferNegotiationNotificationEvent(
                OfferNegotiationNotificationEvent.Trigger.MANAGER_REJECT_TO_CLIENT,
                offer.getId(),
                projectId(offer),
                requestingClientUserId(request)));
        return rejection;
    }

    // --- CLIENT: accept a proposal (R4.5, R12.4) ---

    /**
     * Records a client acceptance of an open {@link NegotiationRoundKind#MANAGER_PROPOSAL} and
     * materializes it into applied {@link OfferDiscountEntity}(s): creates a discount from the
     * proposal's scope/kind/value with {@code sourceRound} pointing at the proposal, applies the
     * scope override-and-cancel hierarchy (superseding the narrower OPEN propositions AND removing
     * the narrower already-materialized discounts a broader acceptance subsumes), marks the proposal
     * {@link NegotiationRoundStatus#ACCEPTED}, bumps the offer {@code revision} by one, and recomputes
     * the offer totals (R4.5/R12.4).
     *
     * <p>The discount is <b>system-materialized</b> from the accepted proposal, not a manager-authored
     * discount write, so it is created directly here rather than through
     * {@code OfferDiscountService.add} (whose executor-only writer guard would block a CLIENT
     * acceptance). The escalation gate is re-checked so an over-threshold proposal is never
     * materialized without ADMIN approval (Property 11).
     *
     * @param roundId the open manager-proposal round being accepted
     * @return the accepted {@code MANAGER_PROPOSAL} round
     * @throws ForemenApiException 403 non-client caller; 404 offer/round missing; 409 terminal offer,
     *                             resolved round, wrong round kind, or un-approved escalation
     */
    @Transactional
    public OfferNegotiationRoundEntity clientAccept(Long roundId) {
        OfferNegotiationRoundEntity proposal = resolveRound(roundId);
        OfferEntity offer = proposal.getOffer();
        assertClient();
        offerService.assertNonTerminal(offer);
        assertRoundOpen(proposal);

        // Only a manager proposal carries a figure to accept.
        if (proposal.getKind() != NegotiationRoundKind.MANAGER_PROPOSAL) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ROUND_RESOLVED_MESSAGE);
        }

        // Property 11: an over-threshold proposal may be materialized only with ADMIN approval.
        BigDecimal scopeBase = scopeBase(offer, proposal.getScope(), proposal.getTargetId());
        boolean escalates = escalationPolicy.requiresAdminApproval(
                proposal.getValueKind(), proposal.getValue(), scopeBase, resolveProjectSettings(offer));
        if (escalates && !proposal.isAdminApproved()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ESCALATION_REQUIRED_MESSAGE);
        }

        // Record the client acceptance on the proposal round itself (R4.5).
        proposal.setStatus(NegotiationRoundStatus.ACCEPTED);

        // R4.5/R4.9: materialize the proposal into an applied discount, removing the narrower
        // already-materialized discounts a broader-scope acceptance subsumes (override-and-cancel).
        materializeDiscount(offer, proposal);

        // R4.5/R12.4: bump the revision by exactly one and recompute totals.
        offer.setRevision(offer.getRevision() == null ? 1 : offer.getRevision() + 1);
        offerService.recomputeTotals(offer);

        offerDao.save(offer);
        entityManager.flush();
        return proposal;
    }

    // --- CLIENT: decline a proposal (R4.4) ---

    /**
     * Records a client decline of an open {@link NegotiationRoundKind#MANAGER_PROPOSAL}: marks the
     * proposal {@link NegotiationRoundStatus#DECLINED} and appends a
     * {@link NegotiationRoundKind#CLIENT_DECLINE} round, which reopens the manager's next-response
     * window (the manager may then propose a different figure or reject with a reasoned explanation,
     * R4.4). No discount is materialized and the offer revision is unchanged.
     *
     * @param roundId the open manager-proposal round being declined
     * @return the persisted {@code CLIENT_DECLINE} round
     * @throws ForemenApiException 403 non-client caller; 404 offer/round missing; 409 terminal offer,
     *                             resolved round, or wrong round kind
     */
    @Transactional
    public OfferNegotiationRoundEntity clientDecline(Long roundId) {
        OfferNegotiationRoundEntity proposal = resolveRound(roundId);
        OfferEntity offer = proposal.getOffer();
        assertClient();
        offerService.assertNonTerminal(offer);
        assertRoundOpen(proposal);

        if (proposal.getKind() != NegotiationRoundKind.MANAGER_PROPOSAL) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ROUND_RESOLVED_MESSAGE);
        }

        proposal.setStatus(NegotiationRoundStatus.DECLINED);

        OfferNegotiationRoundEntity decline =
                newRound(offer, NegotiationRoundKind.CLIENT_DECLINE, CLIENT_ROLE);
        decline.setScope(proposal.getScope());
        decline.setTargetId(proposal.getTargetId());
        // A CLIENT_DECLINE round is itself OPEN so the manager can answer it (R4.4).
        decline.setStatus(NegotiationRoundStatus.OPEN);

        persistRound(offer, decline);
        offerDao.save(offer);
        entityManager.flush();
        return decline;
    }

    // --- round factory / persistence ---

    /**
     * Builds a new round bound to the offer's current {@code Offer_Revision} (R4.6) with the next
     * ordinal {@code roundNo}. The {@code createdBy}/{@code createdAt} audit fields are populated by
     * the JPA auditing listener on persist.
     */
    private OfferNegotiationRoundEntity newRound(OfferEntity offer, NegotiationRoundKind kind, String initiatorRole) {
        OfferNegotiationRoundEntity round = new OfferNegotiationRoundEntity();
        round.setOffer(offer);
        round.setOfferRevision(offer.getRevision() == null ? 1 : offer.getRevision()); // R4.6
        round.setRoundNo(nextRoundNo(offer));
        round.setInitiatorRole(initiatorRole);
        round.setKind(kind);
        return round;
    }

    /** The next 1-based ordinal within the offer's thread (rounds are never deleted, R4.6). */
    private int nextRoundNo(OfferEntity offer) {
        List<OfferNegotiationRoundEntity> rounds = offer.getNegotiationRounds();
        return rounds == null ? 1 : rounds.size() + 1;
    }

    /** Persists a round and links it into the offer's collection so it is visible in-transaction. */
    private void persistRound(OfferEntity offer, OfferNegotiationRoundEntity round) {
        OfferNegotiationRoundEntity saved = roundDao.save(round);
        if (offer.getNegotiationRounds() != null && !offer.getNegotiationRounds().contains(saved)) {
            offer.getNegotiationRounds().add(saved);
        }
    }

    // --- scope override-and-cancel (R4.9) ---

    /**
     * Marks the still-{@code OPEN} narrower-scope propositions subsumed by a newly-created
     * broader-scope proposition as {@link NegotiationRoundStatus#SUPERSEDED} (R4.9): a
     * {@code CATEGORY} proposition supersedes the {@code LINE} propositions in its work-type group; a
     * {@code GLOBAL} proposition supersedes ALL {@code LINE} and {@code CATEGORY} propositions. The
     * {@code newRound} itself and non-proposition rounds are left untouched.
     */
    private void supersedeNarrowerOpenRounds(OfferEntity offer,
                                             DiscountScope newScope,
                                             Long newTargetId,
                                             OfferNegotiationRoundEntity newRound) {
        if (newScope == null || offer.getNegotiationRounds() == null) {
            return;
        }
        for (OfferNegotiationRoundEntity existing : offer.getNegotiationRounds()) {
            if (existing == newRound || existing.getStatus() != NegotiationRoundStatus.OPEN) {
                continue;
            }
            if (!isProposition(existing)) {
                continue;
            }
            if (subsumes(newScope, newTargetId, existing.getScope(), existing.getTargetId())) {
                existing.setStatus(NegotiationRoundStatus.SUPERSEDED);
            }
        }
    }

    /** A proposition round is a client request or a manager proposal (both carry a scope). */
    private static boolean isProposition(OfferNegotiationRoundEntity round) {
        return round.getKind() == NegotiationRoundKind.DISCOUNT_REQUEST
                || round.getKind() == NegotiationRoundKind.MANAGER_PROPOSAL;
    }

    /**
     * Whether a proposition at {@code broaderScope}/{@code broaderTarget} subsumes one at
     * {@code narrowerScope}/{@code narrowerTarget} per the override hierarchy: {@code GLOBAL} subsumes
     * every {@code CATEGORY} and {@code LINE}; a {@code CATEGORY} subsumes the {@code LINE}s of its
     * work-type group (the {@code LINE}'s covering category cannot be determined from the round alone,
     * so a {@code CATEGORY} proposition supersedes {@code LINE} propositions targeting that category —
     * matching how {@code DiscountResolver} collapses by scope).
     */
    private static boolean subsumes(DiscountScope broaderScope, Long broaderTarget,
                                    DiscountScope narrowerScope, Long narrowerTarget) {
        if (narrowerScope == null) {
            return false;
        }
        return switch (broaderScope) {
            case GLOBAL -> narrowerScope == DiscountScope.CATEGORY || narrowerScope == DiscountScope.LINE;
            case CATEGORY ->
                    // A CATEGORY supersedes LINE propositions whose target is that category. Line-vs-
                    // category membership is resolved authoritatively by DiscountResolver at totals
                    // time; here we cancel the same-target LINE requests the category covers.
                    narrowerScope == DiscountScope.LINE
                            && broaderTarget != null && broaderTarget.equals(narrowerTarget);
            case LINE -> false;
        };
    }

    // --- discount materialization (R4.5) ---

    /**
     * Materializes the accepted {@code proposal} into an applied {@link OfferDiscountEntity} and
     * applies the override-and-cancel hierarchy to the already-materialized discounts (R4.5/R4.9):
     * a broader-scope acceptance removes the narrower discounts it subsumes (a {@code GLOBAL}
     * acceptance removes all {@code CATEGORY}/{@code LINE} discounts; a {@code CATEGORY} acceptance
     * removes the {@code LINE} discounts targeting that category), so only the surviving discounts
     * remain and {@link DiscountResolver} resolves a consistent per-line effective discount.
     */
    private void materializeDiscount(OfferEntity offer, OfferNegotiationRoundEntity proposal) {
        // Override-and-cancel on already-applied discounts subsumed by this broader-scope acceptance.
        if (offer.getDiscounts() != null && proposal.getScope() != null) {
            List<OfferDiscountEntity> subsumed = offer.getDiscounts().stream()
                    .filter(d -> subsumes(proposal.getScope(), proposal.getTargetId(), d.getScope(), d.getTargetId()))
                    .toList();
            for (OfferDiscountEntity d : subsumed) {
                offer.getDiscounts().remove(d);
                offerDiscountDao.delete(d);
            }
        }

        OfferDiscountEntity discount = new OfferDiscountEntity();
        discount.setOffer(offer);
        discount.setScope(proposal.getScope());
        discount.setTargetId(proposal.getTargetId());
        discount.setKind(proposal.getValueKind());
        discount.setValue(proposal.getValue());
        discount.setSourceRound(proposal);

        OfferDiscountEntity saved = offerDiscountDao.save(discount);
        if (offer.getDiscounts() != null && !offer.getDiscounts().contains(saved)) {
            offer.getDiscounts().add(saved);
        }
    }

    // --- scope base (for escalation gating, mirrors OfferDiscountService) ---

    /**
     * The net subtotal of a proposition's scope base, computed live from the offer's referenced
     * estimate lines: the whole estimate net for {@code GLOBAL}, the category subtotal for
     * {@code CATEGORY}, the line net for {@code LINE}. A {@code null} scope or an unmatched target
     * yields a zero base.
     */
    private BigDecimal scopeBase(OfferEntity offer, DiscountScope scope, Long targetId) {
        if (scope == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = BigDecimal.ZERO;
        for (EstimateLineEntity line : estimateLines(offer)) {
            if (line == null) {
                continue;
            }
            BigDecimal net = normalizeNet(line.getValueNet());
            switch (scope) {
                case GLOBAL -> base = base.add(net);
                case CATEGORY -> {
                    if (targetId != null && targetId.equals(categoryIdOf(line))) {
                        base = base.add(net);
                    }
                }
                case LINE -> {
                    if (targetId != null && targetId.equals(line.getId())) {
                        base = base.add(net);
                    }
                }
            }
        }
        return base;
    }

    private static List<EstimateLineEntity> estimateLines(OfferEntity offer) {
        EstimateEntity estimate = offer.getEstimate();
        if (estimate == null || estimate.getLines() == null) {
            return List.of();
        }
        return estimate.getLines();
    }

    private static Long categoryIdOf(EstimateLineEntity line) {
        if (line.getWorkItem() == null || line.getWorkItem().getWorkCategory() == null) {
            return null;
        }
        return line.getWorkItem().getWorkCategory().getId();
    }

    private static BigDecimal normalizeNet(BigDecimal net) {
        return net != null && net.signum() > 0 ? net : BigDecimal.ZERO;
    }

    private OfferProjectSettingsEntity resolveProjectSettings(OfferEntity offer) {
        if (offer.getProject() == null || offer.getProject().getId() == null) {
            return null;
        }
        return offerProjectSettingsDao.findByProjectId(offer.getProject().getId()).orElse(null);
    }

    // --- notification-recipient resolution (R14) ---

    /** The owning project id of the offer, or {@code null} when unresolved. */
    private static Long projectId(OfferEntity offer) {
        return offer.getProject() != null ? offer.getProject().getId() : null;
    }

    /**
     * The requesting client's user id for a manager response — the {@code createdBy} of the request
     * round the manager is answering (a client {@code DISCOUNT_REQUEST} or {@code CLIENT_DECLINE},
     * whose {@code createdBy} is the acting client's numeric principal, R14.2/R14.3). Returns
     * {@code null} when it cannot be parsed, in which case the emitter simply skips (best-effort).
     */
    private static Long requestingClientUserId(OfferNegotiationRoundEntity request) {
        return request == null ? null : parseUserId(request.getCreatedBy());
    }

    // --- resolution + guards ---

    private OfferEntity resolveOffer(Long offerId) {
        return offerDao.findById(offerId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "offerId", offerId));
    }

    private OfferNegotiationRoundEntity resolveRound(Long roundId) {
        return roundDao.findById(roundId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "roundId", roundId));
    }

    /** R4.7: only an {@code OPEN} round may be acted on; a resolved round is rejected with 409. */
    private void assertRoundOpen(OfferNegotiationRoundEntity round) {
        if (round.getStatus() != NegotiationRoundStatus.OPEN) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ROUND_RESOLVED_MESSAGE);
        }
    }

    /** Asserts the acting caller is a CLIENT; otherwise 403. */
    private void assertClient() {
        if (!CLIENT_ROLE.equals(resolveActorRole())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
    }

    /**
     * Asserts the acting caller is an executor (MANAGER/ADMIN); otherwise 403. Returns whether the
     * caller is an ADMIN (used to satisfy the escalation gate).
     */
    private boolean assertManager() {
        String role = resolveActorRole();
        if (role == null || !OfferStatusMachine.executorRoles().contains(role)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
        return ADMIN_ROLE.equals(role);
    }

    /**
     * Resolves the acting caller's role code from the security context, mirroring
     * {@link OfferService}/{@link OfferDiscountService}: {@code "ADMIN"} when the authentication
     * carries the ADMIN authority, otherwise the role code of the user identified by the numeric
     * principal name; {@code null} when no role can be resolved.
     */
    private String resolveActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || ADMIN_ROLE.equals(a)) {
                return ADMIN_ROLE;
            }
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            return null;
        }
        UserEntity user = userDao.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        RoleEntity role = user.getRole();
        return role != null ? role.getCode() : null;
    }

    private static Long parseUserId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
