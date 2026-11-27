package com.foremen.dao.model;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
/**
 * FOR-07-04a (Requirement 15.1) — provider-level state of a bank feed. Maps
 * {@code bank_feed_provider_state} (changeset {@code 254-fintable-provider.xml}): PK = the
 * {@link FeedProvider}, so there is at most one row per provider. Holds the provider token expiry
 * date and the last notified level (30 | 7 | 0 | null).
 *
 * <p>The id is assigned, so it does not extend {@link BaseEntity} (identity {@code Long} id); it
 * carries the same audit columns and implements {@link Persistable} so {@code save()} inserts a new
 * row without a pre-merge select.
 */
@Entity
@Table(name = "bank_feed_provider_state")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class BankFeedProviderStateEntity implements Persistable<FeedProvider> {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private FeedProvider provider;
    @Column(name = "token_expires_on")
    private LocalDate tokenExpiresOn;
    /** Last token-expiry notification level sent: 30, 7, 0, or null when none. */
    @Column(name = "token_notified_level")
    private Integer tokenNotifiedLevel;
    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;
    @LastModifiedDate
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
    @Transient
    private boolean persisted;
    @Override
    public FeedProvider getId() {
        return provider;
    }
    @Override
    public boolean isNew() {
        return !persisted;
    }
    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }
}
