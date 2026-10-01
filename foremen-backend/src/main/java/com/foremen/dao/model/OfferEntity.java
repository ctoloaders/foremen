package com.foremen.dao.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-07 (Requirements 1.1, 1.3, 1.4, 1.5, 3.1, 7.1, 19.4): the Offer aggregate — a priced
 * proposal built from a {@code PRICED} estimate and sent to the client. Maps the {@code offers}
 * table (changeset {@code 128-create-offers.xml}).
 *
 * <p>The offer is project-scoped ({@code getProjectIdPath() = "project.id"}) and holds a <b>live
 * reference</b> to its {@link #estimate}: it stores NO per-line price copy — the
 * {@link #totalNet}/{@link #totalVat}/{@link #totalGross} columns are a derived convenience cache
 * recomputed from the referenced estimate plus applied discounts on every change (R1.3 / R19.4), not
 * a frozen copy. A partial unique index enforces at most one non-terminal offer per project (R1.4).
 * {@link #selectedPackage} is seeded from the estimate's applied package and may be null (R1.6/R1.7).
 *
 * <p>{@link #discounts} and {@link #negotiationRounds} are cascade/collection children of the offer
 * ({@code cascade = ALL}, {@code orphanRemoval = true}), mirroring the DB-level ON DELETE CASCADE on
 * their owner FKs.
 */
@Entity
@Table(name = "offers")
@Getter
@Setter
@NoArgsConstructor
public class OfferEntity extends BaseEntity {

    /** Scope root FK to the owning project, NOT NULL; {@code getProjectIdPath() = "project.id"} (R1.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** LIVE reference FK to the estimate, NOT NULL; the offer stores NO per-line price copy (R1.3, R19.4). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "estimate_id", nullable = false)
    private EstimateEntity estimate;

    /** Selected commercial package, seeded from the estimate's applied package; nullable (R1.6/R1.7). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_package_id")
    private OfferPackageEntity selectedPackage;

    /** Monotonically increasing revision; defaults to 1 on creation (R1.5). */
    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    /** Lifecycle status (R3.1); {@code APPROVED}/{@code REJECTED}/{@code WITHDRAWN} are terminal. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OfferStatus status;

    /** The Agreed_Offer_Version recorded at {@code APPROVED}; nullable until then (R7.1). */
    @Column(name = "approved_revision")
    private Integer approvedRevision;

    /** Derived net total = referenced estimate net − Σ effective discounts (R1.3); recomputed, never a frozen copy. */
    @Column(name = "total_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalNet = BigDecimal.ZERO;

    /** Derived VAT total from the discounted net at the project VAT rate (R1.3); recomputed. */
    @Column(name = "total_vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalVat = BigDecimal.ZERO;

    /** Derived gross total = totalNet + totalVat (R1.3); recomputed, never a frozen copy. */
    @Column(name = "total_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalGross = BigDecimal.ZERO;

    /**
     * Applied discount lines of this offer — a cascade/collection child (R2.1). {@code cascade = ALL}
     * + {@code orphanRemoval = true} so discounts persist/remove with the offer, mirroring the
     * DB-level ON DELETE CASCADE on {@code offer_discounts.offer_id}.
     */
    @OneToMany(mappedBy = "offer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OfferDiscountEntity> discounts = new ArrayList<>();

    /**
     * The ordered, never-deleted negotiation thread — a cascade/collection child (R4.1, R4.6).
     * {@code cascade = ALL} + {@code orphanRemoval = true} so rounds persist with the offer,
     * mirroring the DB-level ON DELETE CASCADE on {@code offer_negotiation_rounds.offer_id}.
     */
    @OneToMany(mappedBy = "offer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OfferNegotiationRoundEntity> negotiationRounds = new ArrayList<>();
}
