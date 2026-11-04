package com.foremen.dao.model;

import java.time.OffsetDateTime;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-07-04 (Requirement 2.1) — the AES-GCM-encrypted provider secrets of one connection. Maps
 * {@code bank_connection_secrets}: PK = {@code connection_id} (FK → {@code bank_connections}, ON
 * DELETE CASCADE), no {@code BaseEntity} audit columns — only {@link #updatedAt} / {@link #updatedBy}.
 *
 * <p>The id is assigned (the connection id), so the entity implements {@link Persistable} to let
 * {@code save()} insert a new row without a pre-merge select; {@link #isNew()} is {@code true} until
 * the row is persisted or loaded.
 */
@Entity
@Table(name = "bank_connection_secrets")
@Getter
@Setter
@NoArgsConstructor
public class BankConnectionSecretEntity implements Persistable<Long> {

    /** The owning connection id (PK and FK). */
    @Id
    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    /** 12-byte GCM IV (DB CHECK {@code octet_length(iv) = 12}). */
    @Column(name = "iv", nullable = false)
    private byte[] iv;

    @Column(name = "key_version", nullable = false)
    private short keyVersion;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Transient
    private boolean persisted;

    @Override
    public Long getId() {
        return connectionId;
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
