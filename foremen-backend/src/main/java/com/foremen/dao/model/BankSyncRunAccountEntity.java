package com.foremen.dao.model;

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
 * FOR-07-04 (Requirement 6.2) — per-account counters of a sync run. Maps
 * {@code bank_sync_run_accounts} (changeset {@code 202-create-bank-sync-runs-transactions.xml}),
 * UNIQUE {@code (run_id, account_id)}.
 */
@Entity
@Table(name = "bank_sync_run_accounts")
@Getter
@Setter
@NoArgsConstructor
public class BankSyncRunAccountEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private BankSyncRunEntity run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private BankAccountEntity account;

    @Column(name = "transactions_new", nullable = false)
    private int transactionsNew;

    @Column(name = "transactions_updated", nullable = false)
    private int transactionsUpdated;

    @Column(name = "pending_replaced", nullable = false)
    private int pendingReplaced;

    /** Per-account outcome; nullable (no DB CHECK). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private SyncRunStatus status;

    @Column(name = "error_code", length = 64)
    private String errorCode;
}
