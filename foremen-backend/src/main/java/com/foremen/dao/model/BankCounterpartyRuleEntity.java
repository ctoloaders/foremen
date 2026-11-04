package com.foremen.dao.model;

import java.time.OffsetDateTime;

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
 * FOR-07-04 (Requirement 17.3) — a learned or manual counterparty rule. Maps
 * {@code bank_counterparty_rules} (changeset {@code 203-create-bank-links-suggestions-rules.xml}),
 * UNIQUE {@link #counterpartyKey}. The target fields that apply depend on {@link #targetKind}.
 */
@Entity
@Table(name = "bank_counterparty_rules")
@Getter
@Setter
@NoArgsConstructor
public class BankCounterpartyRuleEntity extends BaseEntity {

    /** {@code IBAN:{iban}} or {@code NAME:{normalized name}}; UNIQUE. */
    @Column(name = "counterparty_key", nullable = false, length = 300)
    private String counterpartyKey;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", nullable = false, length = 24)
    private LinkKind targetKind;

    /** The worker paid (WORKER_PAYOUT rules). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id")
    private UserEntity worker;

    @Column(name = "supplier_name", length = 255)
    private String supplierName;

    @Column(name = "supplier_nip", length = 10)
    private String supplierNip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_schedule_id")
    private PaymentScheduleEntity paymentSchedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unplanned_category_id")
    private UnplannedExpenseCategoryEntity unplannedCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counter_account_id")
    private BankAccountEntity counterAccount;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Times applied, {@code >= 0}. */
    @Column(name = "hits", nullable = false)
    private int hits;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 8)
    private RuleOrigin origin = RuleOrigin.LEARNED;
}
