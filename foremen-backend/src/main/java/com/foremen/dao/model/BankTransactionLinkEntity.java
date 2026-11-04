package com.foremen.dao.model;

import java.math.BigDecimal;
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
 * FOR-07-04 (Requirements 13.1, 13.3) — an applied link of a ledger line to a target. Maps
 * {@code bank_transaction_links} (changeset {@code 203-create-bank-links-suggestions-rules.xml}).
 *
 * <p>A per-{@link LinkKind} DB CHECK requires the target column of the kind and keeps foreign targets
 * empty. {@link #procurementDocument} is ON DELETE SET NULL (the link survives a deleted document), so
 * the service enforces it for {@code PROCUREMENT} on create. {@link #paymentTransaction} is set after
 * client-payment reconciliation and locks the link. {@link #confirmedAt} null ⇒ unconfirmed auto link.
 */
@Entity
@Table(name = "bank_transaction_links")
@Getter
@Setter
@NoArgsConstructor
public class BankTransactionLinkEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private BankTransactionEntity transaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private LinkKind kind;

    /** Linked portion, {@code > 0}. */
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
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransactionEntity paymentTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unplanned_category_id")
    private UnplannedExpenseCategoryEntity unplannedCategory;

    /** The other side of an internal transfer (bank ↔ bank, bank ↔ cash). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counter_account_id")
    private BankAccountEntity counterAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 8)
    private DetectionSource source;

    /** 0..1, nullable. */
    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "confirmed_by", length = 255)
    private String confirmedBy;

    @Column(name = "note", columnDefinition = "text")
    private String note;
}
