package com.foremen.dao.model;

import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-07-04 (Requirements 1.1, 2.1, 3.7) — a bank connection collected through a
 * {@link FeedProvider} (Salt Edge / Stub). Maps {@code bank_connections} (changeset
 * {@code 201-create-bank-connections-accounts.xml}).
 *
 * <p>{@link #providerMode} records the {@link SaltEdgeMode} the connection was created in (null for
 * STUB). {@link #holderNames} keeps the connection-level holder info as JSONB, shaped
 * {@code {"names": [...], "taxIds": [...]}} (mirrors {@code connector.HolderInfo}). Provider secrets
 * live separately in {@link BankConnectionSecretEntity} (encrypted, 1:1).
 */
@Entity
@Table(name = "bank_connections")
@Getter
@Setter
@NoArgsConstructor
public class BankConnectionEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private FeedProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_mode", length = 16)
    private SaltEdgeMode providerMode;

    @Column(name = "provider_code", length = 128)
    private String providerCode;

    @Column(name = "bank_name", length = 255)
    private String bankName;

    @Column(name = "bank_logo_url", length = 512)
    private String bankLogoUrl;

    /** User-editable display name. */
    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "external_customer_id", length = 64)
    private String externalCustomerId;

    /** UNIQUE per provider where not null. */
    @Column(name = "external_connection_id", length = 64)
    private String externalConnectionId;

    @Column(name = "external_consent_id", length = 64)
    private String externalConsentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BankConnectionStatus status;

    @Column(name = "consent_expires_at")
    private OffsetDateTime consentExpiresAt;

    /** The consent id already notified as expiring (once per consent). */
    @Column(name = "consent_expiring_notified_for", length = 64)
    private String consentExpiringNotifiedFor;

    /** Connection-level holder info: {@code {"names": [...], "taxIds": [...]}}; nullable. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "holder_names", columnDefinition = "jsonb")
    private Map<String, Object> holderNames;

    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "last_successful_sync_at")
    private OffsetDateTime lastSuccessfulSyncAt;

    @Column(name = "next_refresh_possible_at")
    private OffsetDateTime nextRefreshPossibleAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    /** Raw provider error text (support only). */
    @Column(name = "last_error_message", columnDefinition = "text")
    private String lastErrorMessage;

    @Column(name = "last_sync_failed_notified_at")
    private OffsetDateTime lastSyncFailedNotifiedAt;
}
