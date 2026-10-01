package com.foremen.service.offer;

import java.time.LocalDateTime;

import com.foremen.dao.model.NotificationEntity;

/**
 * The recipient-facing view of a {@link NotificationEntity} (FOR-05-07, Requirements 13.4, 13.8) —
 * the payload {@code NotificationController} serves for the acting user's own notifications. It
 * exposes only the presentational fields the bell needs and deliberately does <b>not</b> embed the
 * {@code recipient} {@code UserEntity} (ownership is enforced at the service layer by the acting user
 * id, and the recipient is by definition the caller), keeping the response free of the owning-user
 * association.
 *
 * @param id          the notification id
 * @param type        the {@code Notification_Type} i18n key (R13.2)
 * @param body        the message body, or {@code null}
 * @param deepLink    the optional UI target; {@code null} ⇒ non-interactive (R13.10)
 * @param read        whether the notification has been read
 * @param createdDate when the notification was created (the list is newest-first, R13.4)
 */
public record NotificationView(
        Long id,
        String type,
        String body,
        String deepLink,
        boolean read,
        LocalDateTime createdDate) {

    /** Projects a persisted {@link NotificationEntity} onto its recipient-facing view. */
    public static NotificationView of(NotificationEntity entity) {
        return new NotificationView(
                entity.getId(),
                entity.getType(),
                entity.getBody(),
                entity.getDeepLink(),
                entity.isRead(),
                entity.getCreatedDate());
    }
}
