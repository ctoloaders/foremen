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
 * FOR-07-04 (Requirement 13.7) — a detection suggestion for a ledger line. Maps
 * {@code bank_transaction_suggestions} (changeset {@code 203-create-bank-links-suggestions-rules.xml}):
 * the same target shape as {@link BankTransactionLinkEntity} (no payment transaction) plus
 * {@link #rank}, {@link #rationale} and {@link #status}. Targets are ON DELETE CASCADE and the
 * per-kind CHECK requires every target, including the document.
 */
@Entity
@Table(name = "bank_transaction_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class BankTransactionSuggestionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private BankTransactionEntity transaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private LinkKind kind;

    /** Suggested amount, {@code > 0}. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_payout_id")
    private WorkerPayoutEntity workerPayout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "procurement_document_id")
    private ProcurementDocumentEntity procurementDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_schedule_id")
    private PaymentScheduleEntity paymentSchedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unplanned_category_id")
    private UnplannedExpenseCategoryEntity unplannedCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counter_account_id")
    private BankAccountEntity counterAccount;

    /** 1-based rank among the line's suggestions (default 1). */
    @Column(name = "rank", nullable = false)
    private short rank = 1;

    @Column(name = "rationale", length = 300)
    private String rationale;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SuggestionStatus status = SuggestionStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 8)
    private DetectionSource source;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "note", columnDefinition = "text")
    private String note;
}
