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
 * FOR-07-04 (Requirements 5.2, 6.1) — one sync attempt of a connection. Maps {@code bank_sync_runs}
 * (changeset {@code 202-create-bank-sync-runs-transactions.xml}). A partial UNIQUE index allows at
 * most one {@link SyncRunStatus#isActive() active} run per connection.
 *
 * <p>The column {@code "trigger"} is a reserved word in PostgreSQL and is mapped with a quoted name.
 */
@Entity
@Table(name = "bank_sync_runs")
@Getter
@Setter
@NoArgsConstructor
public class BankSyncRunEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private BankConnectionEntity connection;

    @Enumerated(EnumType.STRING)
    @Column(name = "\"trigger\"", nullable = false, length = 16)
    private SyncTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SyncRunStatus status;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** Last {@code notify} stage reported by the provider. */
    @Column(name = "stage", length = 64)
    private String stage;

    @Column(name = "accounts_seen", nullable = false)
    private int accountsSeen;

    @Column(name = "transactions_new", nullable = false)
    private int transactionsNew;

    @Column(name = "transactions_updated", nullable = false)
    private int transactionsUpdated;

    @Column(name = "pending_replaced", nullable = false)
    private int pendingReplaced;

    @Column(name = "rows_skipped", nullable = false)
    private int rowsSkipped;

    @Column(name = "to_review", nullable = false)
    private int toReview;

    @Column(name = "error_class", length = 32)
    private String errorClass;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "requested_by", length = 255)
    private String requestedBy;
}
