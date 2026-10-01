package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-07 (Requirement 13.1): a generic, offer-decoupled, in-app message owned by its recipient
 * user. Maps the {@code notifications} table (changeset {@code 131-create-notifications.xml}).
 *
 * <p>This entity is <b>not</b> project-scoped: ownership is enforced by the recipient user id
 * ({@code actingUserId} at the service layer), never via project filtering (design §Key decision 7,
 * R10.9 / R10.19). It is standalone — it has no offer FK — so any flow can create a notification for
 * any recipient (R13.2). The owner FK {@code recipient_id} is ON DELETE CASCADE at the DB level.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class NotificationEntity extends BaseEntity {

    /** Ownership root FK to the recipient user, NOT NULL (ON DELETE CASCADE at the DB level). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private UserEntity recipient;

    /** {@code Notification_Type} i18n key (R13.2). */
    @Column(name = "type", nullable = false, length = 128)
    private String type;

    /** Message body; nullable. */
    @Column(name = "body")
    private String body;

    /** Optional UI target; null ⇒ non-interactive (R13.10). */
    @Column(name = "deep_link", length = 512)
    private String deepLink;

    /** Unread badge state; defaults to false (unread) on creation (R10.10). */
    @Column(name = "read", nullable = false)
    private boolean read = false;
}
