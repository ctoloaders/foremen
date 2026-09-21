package com.foremen.dao.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
 * The single kosztorys aggregate of a project — one {@code Estimate} per {@code Project} (1:1,
 * FOR-05-03 Requirement 1). Maps the {@code estimates} table (changeset
 * {@code 076-create-estimates.xml}).
 *
 * <p>The {@code project} FK is UNIQUE at the database level, enforcing the single-estimate-per-project
 * invariant (R1.1, R1.6). {@code currency} defaults to PLN and {@code status} defaults to
 * {@link EstimateStatus#DRAFT} on creation — both set at the service layer (R1.2, R1.3). The
 * {@code totalNet}/{@code totalVat}/{@code totalGross} columns are <em>derived</em> by
 * {@code EstimateRecomputeService} (R1.4, R8) and never hand-entered; they default to {@code 0} so a
 * fresh (line-less) estimate reads zero totals (R8.5).
 */
@Entity
@Table(name = "estimates")
@Getter
@Setter
@NoArgsConstructor
public class EstimateEntity extends BaseEntity {

    /** Owning project — UNIQUE FK enforcing the 1:1 invariant (R1.1, R1.6). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private ProjectEntity project;

    /** Estimate currency, defaulted to PLN at the service layer on create (R1.2). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "currency_id", nullable = false)
    private CurrencyEntity currency;

    /** Applicable VAT rate, nullable; {@code coalesce(rate, 0)} is used when deriving VAT (R8.3). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vat_rate_id")
    private VatRateEntity vatRate;

    /** Lifecycle status, defaulting to {@link EstimateStatus#DRAFT} on creation (R1.3). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstimateStatus status = EstimateStatus.DRAFT;

    /** Derived net total = Σ line valueNet (R8.2); never hand-entered. */
    @Column(name = "total_net", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalNet = BigDecimal.ZERO;

    /** Derived VAT total = round2(totalNet × vat) (R8.3); never hand-entered. */
    @Column(name = "total_vat", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalVat = BigDecimal.ZERO;

    /** Derived gross total = round2(totalNet + totalVat) (R8.3); never hand-entered. */
    @Column(name = "total_gross", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalGross = BigDecimal.ZERO;

    /**
     * Read-side view of the estimate's lines, used by {@code EstimateRecomputeService} to
     * traverse {@code estimate.lines} per design §6.1. Not cascaded from this side — lines are
     * owned/persisted by {@code EstimateLineService}; deletion cascade is enforced at the DB
     * level (the {@code estimate_id} FK is {@code ON DELETE CASCADE}).
     */
    @OneToMany(mappedBy = "estimate", fetch = FetchType.LAZY)
    private List<EstimateLineEntity> lines = new ArrayList<>();
}
