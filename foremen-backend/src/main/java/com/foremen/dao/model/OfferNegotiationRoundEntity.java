package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-07 (Requirements 4.1, 4.2, 4.3, 4.6, 4.8, 10.18): one first-class, never-deleted entry of
 * the two-sided offer negotiation thread. Maps the {@code offer_negotiation_rounds} table (changeset
 * {@code 130-create-offer-negotiation-rounds.xml}).
 *
 * <p>Each round is a collection child of its {@link OfferEntity} (owner FK {@code offer_id}, ON
 * DELETE CASCADE) and is bound to the {@link #offerRevision} in effect when it was created (R4.6).
 * The <b>figure is owned by the manager</b>: {@link #valueKind} and {@link #value} are populated
 * <em>only</em> on a {@link NegotiationRoundKind#MANAGER_PROPOSAL} — a client
 * {@link NegotiationRoundKind#DISCOUNT_REQUEST} carries none (R4.3 / R10.18). A
 * {@link NegotiationRoundKind#MANAGER_REJECT} MUST carry a non-blank {@link #explanation} (R4.8).
 * Both invariants are also enforced by DB CHECK constraints on the table.
 */
@Entity
@Table(name = "offer_negotiation_rounds")
@Getter
@Setter
@NoArgsConstructor
public class OfferNegotiationRoundEntity extends BaseEntity {

    /** Owner FK to the offer this round belongs to (ON DELETE CASCADE at the DB level). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offer_id", nullable = false)
    private OfferEntity offer;

    /** The offer revision in effect when this round was created (R4.6). */
    @Column(name = "offer_revision", nullable = false)
    private Integer offerRevision;

    /** Ordinal of the round within the offer. */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** Who opened the round — {@code CLIENT} / {@code MANAGER}. */
    @Column(name = "initiator_role", nullable = false, length = 16)
    private String initiatorRole;

    /** The kind of round; the {@link #value}/{@link #valueKind} figure lives only on a proposal. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private NegotiationRoundKind kind;

    /** Discount scope, on {@code DISCOUNT_REQUEST} / {@code MANAGER_PROPOSAL}; nullable otherwise. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 16)
    private DiscountScope scope;

    /** Scope target id (null for GLOBAL, category id for CATEGORY, line id for LINE); nullable. */
    @Column(name = "target_id")
    private Long targetId;

    /** The proposed discount kind — set ONLY on a {@code MANAGER_PROPOSAL} (R4.3 / R10.18). */
    @Enumerated(EnumType.STRING)
    @Column(name = "value_kind", length = 16)
    private DiscountKind valueKind;

    /** The manager's proposed figure — set ONLY on a {@code MANAGER_PROPOSAL} (R4.3 / R10.18). */
    @Column(name = "value", precision = 14, scale = 4)
    private BigDecimal value;

    /** Free-text justification on a client {@code DISCOUNT_REQUEST}; nullable. */
    @Column(name = "justification")
    private String justification;

    /** Reasoned explanation — NON-BLANK on a {@code MANAGER_REJECT} (R4.8); nullable otherwise. */
    @Column(name = "explanation")
    private String explanation;

    /** Optional client comment (LINE scope, R4.2); nullable. */
    @Column(name = "client_comment")
    private String clientComment;

    /** Round status — {@code OPEN} / {@code ACCEPTED} / {@code DECLINED} / {@code REJECTED} / {@code SUPERSEDED}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NegotiationRoundStatus status;

    /** Escalation gate: whether an ADMIN approved a proposal above the threshold (R6.3). */
    @Column(name = "admin_approved", nullable = false)
    private boolean adminApproved = false;
}
