package com.foremen.service.offer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.NegotiationRoundStatus;

/**
 * One entry of the ordered two-sided negotiation thread in a read model (FOR-05-07, Requirements
 * 4.1, 4.3, 10.18, 15.1).
 *
 * <p>Confidentiality-safe projection of an {@code OfferNegotiationRound}: the {@code valueKind}/
 * {@code value} figure it carries is the <b>discount figure</b> a {@code MANAGER_PROPOSAL} owns
 * (R4.3/R10.18) — an offer-level negotiation fact the client is a party to — not any estimate cost,
 * margin, or worker rate. A client {@code DISCOUNT_REQUEST} carries no figure. It holds <b>no</b>
 * cost, margin, worker rate, or estimate-internal unit-price field (Property 21).
 *
 * @param roundNo       the ordinal of the round within the offer
 * @param offerRevision the offer revision in effect when the round was created
 * @param initiatorRole who opened the round ({@code CLIENT}/{@code MANAGER})
 * @param kind          the round kind
 * @param status        the round resolution status
 * @param scope         the discount scope on a request/proposal, or {@code null}
 * @param targetId      the scope target id, or {@code null}
 * @param valueKind     the proposed discount kind — present ONLY on a {@code MANAGER_PROPOSAL}
 * @param value         the manager's proposed discount figure — present ONLY on a proposal
 * @param justification the client's free-text justification on a request, or {@code null}
 * @param explanation   the manager's reasoned explanation on a rejection, or {@code null}
 * @param clientComment the optional client comment (LINE scope), or {@code null}
 * @param createdDate   when the round was created
 */
public record NegotiationRoundView(
        Integer roundNo,
        Integer offerRevision,
        String initiatorRole,
        NegotiationRoundKind kind,
        NegotiationRoundStatus status,
        DiscountScope scope,
        Long targetId,
        DiscountKind valueKind,
        BigDecimal value,
        String justification,
        String explanation,
        String clientComment,
        LocalDateTime createdDate) {
}
