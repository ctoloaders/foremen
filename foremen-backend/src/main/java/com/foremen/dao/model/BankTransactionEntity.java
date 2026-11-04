package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * FOR-07-04 (Requirements 6.1, 6.2, 13.1, 17.3, 18.1, 27.1, 27.2) — a ledger line, bank or cash. Maps
 * {@code bank_transactions} (changeset {@code 202-create-bank-sync-runs-transactions.xml}).
 *
 * <p>DB CHECKs: {@link #channel} {@code BANK} ⇒ {@link #origin} {@code BANK_FEED} (with a required
 * {@link #externalId}, UNIQUE per account); {@code CASH} ⇒ {@code MANUAL} / {@code SYSTEM} and
 * {@code POSTED}. A {@code SYSTEM} line carries {@link #systemSource} + {@link #systemSourceId}
 * (partially UNIQUE). {@link #amount} is signed and non-zero; {@link #bookingDate} is required when
 * {@code POSTED}. {@link #linkStatus} is a derived cache (written only via {@code LinkStatusDeriver});
 * {@link #ignoredReason} is non-null iff {@code IGNORED}.
 */
@Entity
@Table(name = "bank_transactions")
@Getter
@Setter
@NoArgsConstructor
public class BankTransactionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private BankAccountEntity account;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 8)
    private LedgerChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 10)
    private LedgerOrigin origin;

    /** Provider transaction id; required for BANK_FEED. */
    @Column(name = "external_id", length = 64)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_source", length = 32)
    private SystemSource systemSource;

    /** Payout id / procurement document id of a SYSTEM line. */
    @Column(name = "system_source_id")
    private Long systemSourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private BankTxnStatus status;

    /** Signed amount, never zero. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "operation_date", nullable = false)
    private LocalDate operationDate;

    @Column(name = "booking_date")
    private LocalDate bookingDate;

    @Column(name = "title", columnDefinition = "text")
    private String title;

    @Column(name = "counterparty_name", length = 255)
    private String counterpartyName;

    /** Normalized IBAN. */
    @Column(name = "counterparty_iban", length = 34)
    private String counterpartyIban;

    /** {@code IBAN:{iban}} or {@code NAME:{normalized name}}. */
    @Column(name = "counterparty_key", length = 300)
    private String counterpartyKey;

    @Column(name = "provider_category", length = 64)
    private String providerCategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "counterparty_raw", columnDefinition = "jsonb")
    private Map<String, Object> counterpartyRaw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_payload", columnDefinition = "jsonb")
    private Map<String, Object> providerPayload;

    /** Derived cache, default {@code UNLINKED}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "link_status", nullable = false, length = 20)
    private LinkStatus linkStatus = LinkStatus.UNLINKED;

    @Column(name = "ignored_reason", columnDefinition = "text")
    private String ignoredReason;

    @Column(name = "ignored_by", length = 255)
    private String ignoredBy;

    @Column(name = "ignored_at")
    private OffsetDateTime ignoredAt;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    /** Once-per-transaction AI guard (R15.6). */
    @Column(name = "ai_classified_at")
    private OffsetDateTime aiClassifiedAt;

    @Column(name = "ingested_at", nullable = false)
    private OffsetDateTime ingestedAt = OffsetDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt = OffsetDateTime.now();

    /** The run that first ingested the line (counts {@code toReview}); ON DELETE SET NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "first_sync_run_id")
    private BankSyncRunEntity firstSyncRun;

    /** Author of a MANUAL entry. */
    @Column(name = "created_by_user", length = 255)
    private String createdByUser;
}
