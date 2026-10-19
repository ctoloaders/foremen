package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.foremen.service.payment.PaymentChannel;
import com.foremen.service.payment.TransactionStatus;

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
 * FOR-06-07 (Requirements 5.8, 7.4, 8.4, 5.10): one recorded incoming payment against a project's
 * payment schedule, from one of three channels. Maps the {@code payment_transactions} table
 * (changeset {@code 173-create-payment-transactions.xml}).
 *
 * <p>{@link #schedule} is <b>nullable</b>: a null link marks an ingested bank transaction that could
 * not be correlated to any schedule — recorded "unmatched" and surfaced for manual correlation
 * without failing ingest (R8.4). {@link #externalRef} is the bank / przelewy24 reference used for
 * reconciliation idempotency (R5.8), null for a {@code MANUAL} record that carries none; the
 * {@code (schedule, channel, external_ref) WHERE external_ref IS NOT NULL} partial unique index
 * enforces it at the DB layer. {@link #feeGross} is the commission under the {@code Payer_Borne_Fee}
 * model — recorded for reporting, never deducted from the amount allocated to tranches (R7.4).
 * {@link #recordedBy} is the acting operator for a {@code MANUAL} settlement / mark-paid (R5.10).
 *
 * <p>{@link #allocations} is the owned child collection with {@code cascade = ALL} and
 * {@code orphanRemoval = true}, mirroring the DB-level ON DELETE CASCADE on
 * {@code tranche_allocations.transaction_id}. A single confirmed transaction may span several
 * tranches on an overpayment carry-over.
 */
@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentTransactionEntity extends BaseEntity {

    /** The settled schedule; NULL while an ingested bank transaction is unmatched (R8.4). ON DELETE SET NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private PaymentScheduleEntity schedule;

    /** {@code BANK_TRANSFER}/{@code ONLINE}/{@code MANUAL}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private PaymentChannel channel;

    /** Bank / przelewy24 reference; the reconciliation idempotency key (R5.8); null for a bare MANUAL record. */
    @Column(name = "external_ref", length = 255)
    private String externalRef;

    /** Funds received; DB CHECK >= 0. */
    @Column(name = "amount_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountGross;

    /** Commission, client-borne, never deducted from the allocated amount (R7.4); DB CHECK >= 0. */
    @Column(name = "fee_gross", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeGross = BigDecimal.ZERO;

    /** When the funds were received. {@code TIMESTAMP WITH TIME ZONE} → {@link OffsetDateTime}. */
    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    /** {@code PENDING}/{@code CONFIRMED}/{@code FAILED}/{@code REFUNDED}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    /** Optional free-text on a MANUAL record / mark-paid (R5.10). */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** The acting operator for a MANUAL settlement; nullable (R5.10). ON DELETE SET NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id")
    private UserEntity recordedBy;

    /**
     * How this transaction was applied to tranches — a transaction may span several tranches on an
     * overpayment carry-over (R5.4). Cascades all operations and removes orphans, mirroring the
     * DB-level ON DELETE CASCADE on {@code tranche_allocations.transaction_id}.
     */
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TrancheAllocationEntity> allocations = new ArrayList<>();
}
