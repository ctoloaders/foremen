package com.foremen.controller;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.service.offer.ClientOfferReadModel;
import com.foremen.service.offer.ClientOfferReadModelAssembler;
import com.foremen.service.offer.ExecutorOfferReadModel;
import com.foremen.service.offer.NegotiationService;
import com.foremen.service.offer.OfferVisibilityResolver;

import lombok.RequiredArgsConstructor;

/**
 * The two-sided offer-negotiation controller (FOR-05-07, design §NegotiationService) — the round
 * mutators of the threaded discount negotiation, layered on the same {@code OFFERS} ABAC resource as
 * {@link OfferController} ({@link PermissionResource @PermissionResource} is per-class, so several
 * controllers may share the resource; changeset 133 seeds it).
 *
 * <h2>ABAC guarding (R5.1, R5.3)</h2>
 * The class carries {@link PermissionResource @PermissionResource("OFFERS")}; every handler carries a
 * method-level {@link RequiresPermission @RequiresPermission} that takes precedence over the class
 * default (mirroring {@link EstimateMatrixController}), so the controller is <b>fully annotated</b>
 * and {@code PermissionAnnotationValidator} classifies it COMPLETE at startup. The round mutators
 * split across TWO ABAC operations by who initiates the round, because a CLIENT holds only
 * {@code OFFERS READ + APPROVE} (never UPDATE) per Req 5.2: client-initiated rounds
 * (discount-request / accept / decline) are gated {@code (OFFERS, APPROVE)} (gating them on UPDATE
 * would 403 the client before the service role check runs); manager/executor-initiated rounds
 * (propose / reject) stay {@code (OFFERS, UPDATE)}. The <b>role</b> distinction (a client opens
 * requests / accepts / declines; a manager proposes / rejects) is additionally enforced server-side
 * inside {@link NegotiationService} ({@code 403 error.offer.negotiation.forbidden}) as the second
 * gate — a MANAGER calling accept, or a CLIENT calling propose, is still rejected there. ADMIN
 * bypasses ABAC; MANAGER holds both UPDATE and APPROVE (so manager flows are unaffected by the
 * split); ESTIMATOR holds UPDATE (may propose/reject) but not APPROVE (cannot open client requests /
 * accept / decline — correct, those are client actions):
 * <ul>
 *   <li>{@code POST /{offerId}/rounds/discount-request} — CLIENT opens a discount request (no
 *       figure, R4.1); {@code (OFFERS, APPROVE)}.</li>
 *   <li>{@code POST /rounds/{roundId}/propose} — MANAGER proposes a figure (R4.3, escalation-gated,
 *       R6.3/R6.5); {@code (OFFERS, UPDATE)}.</li>
 *   <li>{@code POST /rounds/{roundId}/reject} — MANAGER rejects with a mandatory explanation
 *       (R4.8/R6.6); {@code (OFFERS, UPDATE)}.</li>
 *   <li>{@code POST /rounds/{roundId}/accept} — CLIENT accepts a proposal (materializes discounts,
 *       bumps revision, R4.5); {@code (OFFERS, APPROVE)}.</li>
 *   <li>{@code POST /rounds/{roundId}/decline} — CLIENT declines a proposal (R4.4);
 *       {@code (OFFERS, APPROVE)}.</li>
 * </ul>
 *
 * <p>Each mutator returns the refreshed offer read model (branched by caller audience, like
 * {@link OfferController#getOffer}) so the client re-renders the whole negotiation thread and totals
 * after the round is recorded.
 */
@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
@PermissionResource("OFFERS")
public class OfferNegotiationController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String OFFERS_RESOURCE = "OFFERS";
    private static final String UPDATE_OPERATION = "UPDATE";
    private static final String APPROVE_OPERATION = "APPROVE";
    private static final String CLIENT_ROLE = "CLIENT";

    private final NegotiationService negotiationService;
    private final ClientOfferReadModelAssembler clientOfferReadModelAssembler;
    private final OfferVisibilityResolver offerVisibilityResolver;

    /**
     * CLIENT opens a discount request — a {@code DISCOUNT_REQUEST} round carrying scope + target +
     * justification and NO figure (R4.1/R4.2); drives {@code SENT}/{@code COUNTERED → CHANGES_REQUESTED}
     * (R3.3). {@code OFFERS} APPROVE — a client-initiated round; the CLIENT holds READ+APPROVE, not
     * UPDATE (Req 5.2), so this is gated by APPROVE (client role additionally enforced server-side).
     */
    @PostMapping("/{offerId}/rounds/discount-request")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = APPROVE_OPERATION)
    public ResponseEntity<?> openDiscountRequest(
            @PathVariable Long offerId, @RequestBody DiscountRequest request) {
        OfferNegotiationRoundEntity round = negotiationService.openDiscountRequest(
                offerId,
                request != null ? request.scope() : null,
                request != null ? request.targetId() : null,
                request != null ? request.justification() : null,
                request != null ? request.clientComment() : null);
        return readModelOf(round);
    }

    /**
     * MANAGER proposes a figure answering an open request — a {@code MANAGER_PROPOSAL} carrying the
     * kind + value (R4.3); escalation-gated (R6.3/R6.5); drives {@code CHANGES_REQUESTED → COUNTERED}
     * (R3.4). {@code OFFERS} UPDATE — a manager/ESTIMATOR-initiated round (executors hold UPDATE);
     * executor role enforced server-side.
     */
    @PostMapping("/rounds/{roundId}/propose")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<?> propose(
            @PathVariable Long roundId, @RequestBody ProposeRequest request) {
        OfferNegotiationRoundEntity round = negotiationService.managerPropose(
                roundId,
                request != null ? request.kind() : null,
                request != null ? request.value() : null);
        return readModelOf(round);
    }

    /**
     * MANAGER rejects an open request with a mandatory non-blank {@code explanation} (R4.8/R6.6 →
     * {@code 400 error.offer.reject.explanation.required}). {@code OFFERS} UPDATE — a
     * manager/ESTIMATOR-initiated round (executors hold UPDATE); executor role enforced server-side.
     */
    @PostMapping("/rounds/{roundId}/reject")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = UPDATE_OPERATION)
    public ResponseEntity<?> reject(
            @PathVariable Long roundId, @RequestBody RejectRequest request) {
        OfferNegotiationRoundEntity round = negotiationService.managerReject(
                roundId, request != null ? request.explanation() : null);
        return readModelOf(round);
    }

    /**
     * CLIENT accepts an open manager proposal — materializes it into applied discounts (scope
     * override-and-cancel) and bumps the revision (R4.5/R12.4). {@code OFFERS} APPROVE — a
     * client-initiated round (the CLIENT holds READ+APPROVE, not UPDATE, Req 5.2); client role
     * additionally enforced server-side.
     */
    @PostMapping("/rounds/{roundId}/accept")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = APPROVE_OPERATION)
    public ResponseEntity<?> accept(@PathVariable Long roundId) {
        OfferNegotiationRoundEntity round = negotiationService.clientAccept(roundId);
        return readModelOf(round);
    }

    /**
     * CLIENT declines an open manager proposal — reopens the manager's next-response window (R4.4).
     * {@code OFFERS} APPROVE — a client-initiated round (the CLIENT holds READ+APPROVE, not UPDATE,
     * Req 5.2); client role additionally enforced server-side.
     */
    @PostMapping("/rounds/{roundId}/decline")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = APPROVE_OPERATION)
    public ResponseEntity<?> decline(@PathVariable Long roundId) {
        OfferNegotiationRoundEntity round = negotiationService.clientDecline(roundId);
        return readModelOf(round);
    }

    /**
     * Assembles the audience-appropriate read model of the round's offer (branched like
     * {@link OfferController#getOffer}): a CLIENT is served the confidential {@link ClientOfferReadModel}
     * after the visibility gate; a MANAGER/ADMIN is served the {@link ExecutorOfferReadModel}. Because
     * a round is only ever created/acted on while the offer is client-visible (non-DRAFT), the gate is
     * defensive here.
     */
    private ResponseEntity<?> readModelOf(OfferNegotiationRoundEntity round) {
        OfferEntity offer = round.getOffer();
        if (isClient()) {
            offerVisibilityResolver.assertClientVisible(offer.getStatus(), offer.getId());
            return ResponseEntity.ok(clientOfferReadModelAssembler.toClientReadModel(offer));
        }
        return ResponseEntity.ok(clientOfferReadModelAssembler.toExecutorReadModel(offer, true));
    }

    /** Whether the authenticated caller's role is CLIENT (selects the confidential read model). */
    private boolean isClient() {
        return CLIENT_ROLE.equals(currentRoleCode());
    }

    /**
     * The authenticated caller's role code, read from the {@code ROLE_<code>} authority in the
     * security context, or {@code null} when there is no authenticated principal. Mirrors the
     * extraction used by {@link EstimateMatrixController} / {@code PermissionInterceptor}.
     */
    private String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }

    /** Request body for opening a client discount request (no figure — the figure is the manager's). */
    public record DiscountRequest(
            DiscountScope scope, Long targetId, String justification, String clientComment) {
    }

    /** Request body for a manager proposal: the proposed discount kind and figure. */
    public record ProposeRequest(DiscountKind kind, BigDecimal value) {
    }

    /** Request body for a manager rejection: the mandatory reasoned explanation. */
    public record RejectRequest(String explanation) {
    }
}
