package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.NotificationEntity;

/**
 * DAO for {@link NotificationEntity} (FOR-05-07, Requirements 13.1, 13.4, 13.8, 13.9, 10.10),
 * following the {@code OfferDao} finder convention: the generic {@link AdminDao} CRUD surface plus
 * the recipient-scoped derived finders backing the acting-user notification methods in
 * {@code NotificationService}.
 *
 * <p>A {@code Notification} is <b>not</b> project-scoped — ownership is enforced entirely by the
 * recipient user id ({@code actingUserId} at the service layer, R10.9 / R10.19). All read finders
 * therefore constrain on {@code recipient.id} so the acting user only ever sees its own
 * notifications (R13.8 / R13.9).
 */
@Repository
public interface NotificationDao extends AdminDao<NotificationEntity, Long> {

    /**
     * The acting user's own notifications, newest first — backs
     * {@code NotificationService.listForActingUser} (R13.4 / R13.8). Ordered by the {@code createdDate}
     * audit column (from {@code BaseEntity}) descending so the most recent notification is first.
     *
     * @param recipientId the acting (owning) user's id
     * @return the user's notifications, newest first
     */
    List<NotificationEntity> findByRecipientIdOrderByCreatedDateDesc(Long recipientId);

    /**
     * The number of the acting user's <b>unread</b> notifications — backs the bell badge count
     * ({@code NotificationService.unreadCount}, R10.10).
     *
     * @param recipientId the acting (owning) user's id
     * @return the count of the user's unread ({@code read = false}) notifications
     */
    long countByRecipientIdAndReadFalse(Long recipientId);
}
