package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

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
 * FOR-07-04 (Requirements 3.1, 3.7, 3.8, 27.1, 27.2) — a ledger account: a provider-backed
 * {@link BankAccountType#BANK} account or a company {@link BankAccountType#CASH} register. Maps
 * {@code bank_accounts} (changeset {@code 201-create-bank-connections-accounts.xml}).
 *
 * <p>DB CHECKs: BANK has {@link #connection} + {@link #externalId}, CASH has neither;
 * {@link #defaultForCash} only for CASH (partial UNIQUE per currency); CASH is always
 * {@link #includedInLedger}. {@link #holderMatch} is the holder verification result with an optional
 * manual override ({@code holderOverride*}).
 */
@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
@NoArgsConstructor
public class BankAccountEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8)
    private BankAccountType type;

    /** The owning connection; NOT NULL for BANK, NULL for CASH. ON DELETE CASCADE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id")
    private BankConnectionEntity connection;

    /** Provider account id; NOT NULL for BANK, UNIQUE per connection. */
    @Column(name = "external_id", length = 64)
    private String externalId;

    /** Provider name for BANK, user-entered name for CASH. */
    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "nature", length = 64)
    private String nature;

    @Column(name = "holder_name", length = 255)
    private String holderName;

    /** Normalized IBAN; null for CASH. */
    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "account_number", length = 64)
    private String accountNumber;

    /** ISO 4217 code, {@code CHAR(3)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Provider-reported balance (BANK). */
    @Column(name = "balance", precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "available_balance", precision = 19, scale = 2)
    private BigDecimal availableBalance;

    /** CASH opening balance. */
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    /** CASH only: the default register for its currency. */
    @Column(name = "default_for_cash", nullable = false)
    private boolean defaultForCash;

    /** CASH only: archived registers accept no new entries. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    @Column(name = "balance_updated_at")
    private OffsetDateTime balanceUpdatedAt;

    /** Always {@code true} for CASH (DB CHECK). */
    @Column(name = "included_in_ledger", nullable = false)
    private boolean includedInLedger = true;

    @Column(name = "closed_at_provider", nullable = false)
    private boolean closedAtProvider;

    @Column(name = "has_balance_gap", nullable = false)
    private boolean hasBalanceGap;

    @Enumerated(EnumType.STRING)
    @Column(name = "holder_match", nullable = false, length = 10)
    private HolderMatch holderMatch = HolderMatch.UNKNOWN;

    @Column(name = "holder_override_by", length = 255)
    private String holderOverrideBy;

    @Column(name = "holder_override_at")
    private OffsetDateTime holderOverrideAt;

    @Column(name = "holder_override_reason", columnDefinition = "text")
    private String holderOverrideReason;

    /** Last seen provider transaction id. */
    @Column(name = "transactions_cursor", length = 64)
    private String transactionsCursor;
}
