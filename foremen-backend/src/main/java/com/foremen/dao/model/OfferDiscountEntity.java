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
 * FOR-05-07 (Requirements 2.1, 2.2, 4.5): a scoped discount/rebate line applied on top of the
 * offer's live-referenced estimate prices. Maps the {@code offer_discounts} table (changeset
 * {@code 129-create-offer-discounts.xml}).
 *
 * <p>An {@code OfferDiscount} is a collection child of its {@link OfferEntity} (owner FK
 * {@code offer_id}, ON DELETE CASCADE) and resolves to a project via {@code offer.project.id}. Its
 * {@link #value} is DB-guarded {@code >= 0} (R2.2). When a discount is materialized by an accepted
 * negotiation round it back-references that round through {@link #sourceRound} (R4.5); the reference
 * is nullable (a directly-authored discount has none, and removing the round nulls it via ON DELETE
 * SET NULL).
 */
@Entity
@Table(name = "offer_discounts")
@Getter
@Setter
@NoArgsConstructor
public class OfferDiscountEntity extends BaseEntity {

    /** Owner FK to the offer this discount belongs to (ON DELETE CASCADE at the DB level). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offer_id", nullable = false)
    private OfferEntity offer;

    /** Discount scope — {@code GLOBAL} / {@code CATEGORY} / {@code LINE}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private DiscountScope scope;

    /** Scope target id (null for GLOBAL, category id for CATEGORY, estimate line id for LINE). */
    @Column(name = "target_id")
    private Long targetId;

    /** Discount kind — {@code PERCENT} / {@code ABSOLUTE}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private DiscountKind kind;

    /** Discount value; DB-guarded {@code >= 0} (R2.2). */
    @Column(name = "value", nullable = false, precision = 14, scale = 4)
    private BigDecimal value;

    /**
     * The accepted negotiation round that materialized this discount (R4.5); nullable — a
     * directly-authored discount has none, and removing the round nulls this back-reference
     * (ON DELETE SET NULL).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_round_id")
    private OfferNegotiationRoundEntity sourceRound;
}
