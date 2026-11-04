package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-07-04 (Requirement 7.2) — a detected balance-continuity gap of an account between two syncs.
 * Maps {@code bank_balance_gaps} (changeset {@code 201-create-bank-connections-accounts.xml}).
 */
@Entity
@Table(name = "bank_balance_gaps")
@Getter
@Setter
@NoArgsConstructor
public class BankBalanceGapEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private BankAccountEntity account;

    @Column(name = "period_from")
    private OffsetDateTime periodFrom;

    @Column(name = "period_to")
    private OffsetDateTime periodTo;

    @Column(name = "expected", precision = 19, scale = 2)
    private BigDecimal expected;

    @Column(name = "actual", precision = 19, scale = 2)
    private BigDecimal actual;

    @Column(name = "difference", precision = 19, scale = 2)
    private BigDecimal difference;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 255)
    private String acknowledgedBy;

    @Column(name = "note", columnDefinition = "text")
    private String note;
}
