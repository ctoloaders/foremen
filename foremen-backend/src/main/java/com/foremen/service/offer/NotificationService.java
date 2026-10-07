package com.foremen.service.offer;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.NotificationDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;

import jakarta.persistence.EntityManager;

/**
 * Recipient-scoped, offer-decoupled in-app notification service (FOR-05-07, Requirements 13.1, 13.2,
 * 13.4, 13.6, 13.7, 13.8, 13.9, 10.9, 10.10, 10.19; design §NotificationService and §Key decision 7).
 *
 * <p>This is a plain {@link Service} — <b>not</b> a {@code ProjectScopedService}. A
 * {@link NotificationEntity} has no project boundary; ownership is enforced entirely at this layer by
 * the recipient user id supplied by the caller ({@code actingUserId}), never via project filtering
 * (R10.9 / R10.19). It has two audiences:
 *
 * <ul>
 *   <li>{@link #create(Long, String, String, String)} is the <b>generic extension point</b> any flow
 *       calls to notify a user (R13.2) — most notably {@code OfferNotificationEmitter} (task 8.2). It
 *       is not exposed to end users through a CREATE grant.</li>
 *   <li>{@link #listForActingUser(Long)}, {@link #toggleRead(Long, Long)},
 *       {@link #delete(Long, Long)}, and {@link #unreadCount(Long)} are the <b>acting-user</b>
 *       operations {@code NotificationController} (task 9.1) exposes; each is scoped to the caller's
 *       own notifications.</li>
 * </ul>
 *
 * <p><b>Ownership guard.</b> {@link #toggleRead(Long, Long)} and {@link #delete(Long, Long)} reject
 * any notification whose {@code recipient} is not the acting user with {@code 403
 * error.notification.forbidden} (R13.9); reads ({@link #listForActingUser(Long)},
 * {@link #unreadCount(Long)}) only ever return the acting user's own rows (R13.8), so a foreign
 * notification is never even surfaced.
 */
@Service
public class NotificationService {

    /** 404 when the referenced notification / recipient user cannot be resolved. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /**
     * 403 when the acting user tries to toggle-read or delete a notification that belongs to another
     * user (R13.9). The same code is used for both mutators so a foreign notification is
     * indistinguishable to the caller.
     */
    static final String FORBIDDEN_MESSAGE = "error.notification.forbidden";

    private final NotificationDao notificationDao;
    private final UserDao userDao;
    private final EntityManager entityManager;

    public NotificationService(NotificationDao notificationDao,
                               UserDao userDao,
                               EntityManager entityManager) {
        this.notificationDao = notificationDao;
        this.userDao = userDao;
        this.entityManager = entityManager;
    }

    /**
     * Creates a notification for {@code recipientUserId} — the generic, offer-decoupled extension
     * point any flow calls (R13.2). The new notification starts unread ({@code read = false}). This
     * method carries <b>no</b> acting-user ownership check: it is an internal write reached only from
     * server-side flows (e.g. {@code OfferNotificationEmitter}), never from an end-user CREATE grant.
     *
     * <p><b>Transaction semantics.</b> This method runs in its <b>own</b>
     * {@link Propagation#REQUIRES_NEW REQUIRES_NEW} transaction so it can be safely invoked from an
     * {@code AFTER_COMMIT} {@code @TransactionalEventListener} (e.g. {@code OfferNotificationEmitter})
     * where no transaction is active: a plain {@code REQUIRED} propagation would leave the
     * {@code entityManager.flush()} below without a usable transaction and throw
     * {@code TransactionRequiredException}, silently dropping the notification. REQUIRES_NEW suspends
     * any (normally absent) outer transaction and commits the row independently of — and after — the
     * triggering negotiation transaction. This matches the best-effort, decoupled emission design: a
     * notification failure never rolls back or blocks the triggering transition (the emitter wraps
     * this call in try/catch).
     *
     * @param recipientUserId the user to notify (ownership root); must resolve to a user
     * @param type            the {@code Notification_Type} i18n key (R13.2)
     * @param body            the message body; may be {@code null}
     * @param deepLink        optional UI target; {@code null} ⇒ non-interactive (R13.10)
     * @return the persisted notification entity
     * @throws ForemenApiException 404 when {@code recipientUserId} resolves to no user
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationEntity create(Long recipientUserId, String type, String body, String deepLink) {
        UserEntity recipient = userDao.findById(recipientUserId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "recipientUserId", recipientUserId));

        NotificationEntity notification = new NotificationEntity();
        notification.setRecipient(recipient);
        notification.setType(type);
        notification.setBody(body);
        notification.setDeepLink(deepLink);
        notification.setRead(false);

        NotificationEntity saved = notificationDao.save(notification);
        entityManager.flush();
        return saved;
    }

    /**
     * The acting user's own notifications, newest first (R13.4 / R13.8). Because the finder constrains
     * on {@code recipient.id}, no other user's notification can ever appear in the result.
     *
     * @param actingUserId the acting (owning) user's id
     * @return the acting user's notifications ordered by creation time, newest first
     */
    @Transactional(readOnly = true)
    public List<NotificationEntity> listForActingUser(Long actingUserId) {
        return notificationDao.findByRecipientIdOrderByCreatedDateDesc(actingUserId);
    }

    /**
     * Toggles the {@code read} flag of the acting user's own notification (R13.6). If the notification
     * belongs to another user the request is rejected with {@code 403 error.notification.forbidden}
     * (R13.9), never revealing that a foreign notification exists.
     *
     * @param actingUserId   the acting (owning) user's id
     * @param notificationId the notification to toggle
     * @return the updated notification entity with its {@code read} flag flipped
     * @throws ForemenApiException 404 when the notification is missing; 403 when it belongs to another
     *                             user
     */
    @Transactional
    public NotificationEntity toggleRead(Long actingUserId, Long notificationId) {
        NotificationEntity notification = resolveOwned(actingUserId, notificationId);
        notification.setRead(!notification.isRead());
        NotificationEntity saved = notificationDao.save(notification);
        entityManager.flush();
        return saved;
    }

    /**
     * Deletes the acting user's own notification (R13.7). If the notification belongs to another user
     * the request is rejected with {@code 403 error.notification.forbidden} (R13.9), never revealing
     * that a foreign notification exists.
     *
     * @param actingUserId   the acting (owning) user's id
     * @param notificationId the notification to delete
     * @throws ForemenApiException 404 when the notification is missing; 403 when it belongs to another
     *                             user
     */
    @Transactional
    public void delete(Long actingUserId, Long notificationId) {
        NotificationEntity notification = resolveOwned(actingUserId, notificationId);
        notificationDao.delete(notification);
        entityManager.flush();
    }

    /**
     * The bell badge count — the number of the acting user's <b>unread</b> notifications (R10.10).
     * Scoped to the acting user by the recipient-constrained finder.
     *
     * @param actingUserId the acting (owning) user's id
     * @return the count of the acting user's unread notifications
     */
    @Transactional(readOnly = true)
    public long unreadCount(Long actingUserId) {
        return notificationDao.countByRecipientIdAndReadFalse(actingUserId);
    }

    /**
     * Resolves a notification and asserts it is owned by the acting user (R13.9). Ownership is checked
     * against the recipient user id rather than any project scope (R10.9 / R10.19).
     *
     * @param actingUserId   the acting (owning) user's id
     * @param notificationId the notification to resolve
     * @return the owned notification entity
     * @throws ForemenApiException 404 when the notification is missing; 403 when its recipient is not
     *                             the acting user
     */
    private NotificationEntity resolveOwned(Long actingUserId, Long notificationId) {
        NotificationEntity notification = notificationDao.findById(notificationId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "notificationId", notificationId));

        UserEntity recipient = notification.getRecipient();
        Long recipientId = recipient != null ? recipient.getId() : null;
        if (recipientId == null || !recipientId.equals(actingUserId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
        }
        return notification;
    }
}
