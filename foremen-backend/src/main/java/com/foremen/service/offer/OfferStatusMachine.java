package com.foremen.service.offer;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.model.OfferAction;
import com.foremen.dao.model.OfferStatus;
import com.foremen.exception.ForemenApiException;

/**
 * Pure, stateless transition function for the {@code Offer} status machine (FOR-05-07,
 * Requirement 3; design §OfferStatusMachine). This is the <b>single source of truth</b> for the
 * legal offer transitions (R3) and the illegal-transition rejection (R10.5): every offer mutator
 * routes its status change through {@link #transition(OfferStatus, OfferAction, String)} rather than
 * assigning a status directly.
 *
 * <p>The machine encodes exactly the transitions Requirement 3 defines:
 *
 * <table border="1">
 *   <caption>Legal transitions</caption>
 *   <tr><th>From</th><th>Action</th><th>Actor</th><th>To</th><th>Criterion</th></tr>
 *   <tr><td>DRAFT</td><td>SEND</td><td>executor</td><td>SENT</td><td>R3.2</td></tr>
 *   <tr><td>SENT / COUNTERED</td><td>REQUEST_CHANGES</td><td>CLIENT</td><td>CHANGES_REQUESTED</td><td>R3.3</td></tr>
 *   <tr><td>CHANGES_REQUESTED</td><td>PROPOSE</td><td>executor</td><td>COUNTERED</td><td>R3.4</td></tr>
 *   <tr><td>SENT / COUNTERED</td><td>APPROVE</td><td>CLIENT</td><td>APPROVED</td><td>R3.5</td></tr>
 *   <tr><td>SENT / COUNTERED</td><td>REJECT</td><td>CLIENT</td><td>REJECTED</td><td>R3.6</td></tr>
 *   <tr><td>any non-terminal</td><td>WITHDRAW</td><td>executor</td><td>WITHDRAWN</td><td>R3.7</td></tr>
 * </table>
 *
 * <p>Any {@code (current, action, actorRole)} triple not listed above — including <em>any</em>
 * action attempted on a terminal offer ({@link OfferStatus#APPROVED} / {@link OfferStatus#REJECTED}
 * / {@link OfferStatus#WITHDRAWN}) or by the wrong role — is rejected with a localized {@code 409
 * error.offer.illegal.transition} and leaves the offer unchanged (R3.8 / R10.5). The machine holds
 * no state and performs no I/O, mirroring the {@code DraftGateGuard} convention of a stateless
 * Spring {@code @Component}, so it is directly property-testable without persistence.
 */
@Component
public class OfferStatusMachine {

    /** Localized message code for a rejected transition (R3.8 / R10.5). */
    static final String ILLEGAL_TRANSITION_MESSAGE = "error.offer.illegal.transition";

    /**
     * Executor role codes (MANAGER/ADMIN/ESTIMATOR) — the offer/discount side (Glossary: Executor).
     *
     * <p>ESTIMATOR is an executor for offer preparation and the executor offer actions (SEND,
     * PROPOSE, WITHDRAW here; discount writes via {@link OfferDiscountService}; manager negotiation
     * proposals/rejects via {@link NegotiationService}) through the tightened {@code OFFERS}
     * {@code READ}/{@code CREATE}/{@code UPDATE} grant (FOR-05-07, Requirements 5.1, 5.4, 16).
     * ESTIMATOR intentionally has <b>no</b> {@code OFFERS APPROVE} grant: the client-only
     * {@code APPROVE}/{@code REJECT} transitions are predicated on {@link #isClient(String)} (not on
     * executor-hood), and the {@code (OFFERS, APPROVE)} ABAC grant is not seeded for ESTIMATOR, so
     * adding ESTIMATOR here never admits it to approving/rejecting an offer (R5.4).
     */
    private static final Set<String> EXECUTOR_ROLES = Set.of("MANAGER", "ADMIN", "ESTIMATOR");

    /** The single client role code. */
    private static final String CLIENT_ROLE = "CLIENT";

    /** The offer statuses from which a client discount request, approval, or rejection is legal. */
    private static final Set<OfferStatus> NEGOTIABLE =
            EnumSet.of(OfferStatus.SENT, OfferStatus.COUNTERED);

    /**
     * Applies {@code action}, performed by {@code actorRole}, to an offer in state {@code current}
     * and returns the resulting {@link OfferStatus}. The transition is applied <b>iff</b> it is one
     * of the transitions defined by Requirement 3; every other triple is rejected.
     *
     * @param current    the offer's current status (never {@code null})
     * @param action     the transition-driving action (never {@code null})
     * @param actorRole  the acting user's role code (e.g. {@code "MANAGER"}, {@code "CLIENT"},
     *                   {@code "ADMIN"}); may be {@code null}, which is treated as an unauthorized
     *                   actor and rejected
     * @return the next {@link OfferStatus}
     * @throws ForemenApiException 409 {@code error.offer.illegal.transition} when the triple is not
     *                             a legal transition — including any action out of a terminal state
     *                             or by the wrong role (R3.8 / R10.5); the offer is left unchanged
     */
    public OfferStatus transition(OfferStatus current, OfferAction action, String actorRole) {
        // Reject a null/terminal source outright: there is no legal exit from a terminal state
        // (R3.1 / R3.8), so a terminal offer is immutable.
        if (current == null || action == null || current.isTerminal()) {
            throw illegal();
        }

        boolean executor = isExecutor(actorRole);
        boolean client = isClient(actorRole);
        boolean negotiable = NEGOTIABLE.contains(current);

        OfferStatus next = switch (action) {
            // Executor sends a DRAFT offer → SENT (R3.2).
            case SEND -> executor && current == OfferStatus.DRAFT ? OfferStatus.SENT : null;
            // CLIENT opens a discount request on a SENT/COUNTERED offer → CHANGES_REQUESTED (R3.3).
            case REQUEST_CHANGES -> client && negotiable ? OfferStatus.CHANGES_REQUESTED : null;
            // Executor responds to CHANGES_REQUESTED with a proposal → COUNTERED (R3.4).
            case PROPOSE ->
                    executor && current == OfferStatus.CHANGES_REQUESTED ? OfferStatus.COUNTERED : null;
            // CLIENT approves a SENT/COUNTERED offer → APPROVED (R3.5).
            case APPROVE -> client && negotiable ? OfferStatus.APPROVED : null;
            // CLIENT rejects a SENT/COUNTERED offer → REJECTED (R3.6).
            case REJECT -> client && negotiable ? OfferStatus.REJECTED : null;
            // Executor withdraws any non-terminal offer → WITHDRAWN (R3.7); current is already
            // guaranteed non-terminal above.
            case WITHDRAW -> executor ? OfferStatus.WITHDRAWN : null;
        };

        if (next == null) {
            throw illegal();
        }
        return next;
    }

    /**
     * Convenience predicate: whether {@code action} by {@code actorRole} is a legal transition from
     * {@code current} without throwing. Mirrors {@link #transition} exactly.
     */
    public boolean canTransition(OfferStatus current, OfferAction action, String actorRole) {
        try {
            transition(current, action, actorRole);
            return true;
        } catch (ForemenApiException ex) {
            return false;
        }
    }

    private static boolean isExecutor(String actorRole) {
        return actorRole != null && EXECUTOR_ROLES.contains(actorRole);
    }

    private static boolean isClient(String actorRole) {
        return CLIENT_ROLE.equals(actorRole);
    }

    private static ForemenApiException illegal() {
        return new ForemenApiException(HttpStatus.CONFLICT, ILLEGAL_TRANSITION_MESSAGE);
    }

    /** The executor role codes this machine treats as the offer/discount side. */
    public static List<String> executorRoles() {
        return List.copyOf(EXECUTOR_ROLES);
    }
}
