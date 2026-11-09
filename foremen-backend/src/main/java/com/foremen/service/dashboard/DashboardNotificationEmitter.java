package com.foremen.service.dashboard;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.service.offer.NotificationService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * FOR-10 (Requirement 17) — best-effort in-app notifications of the dashboard module, created through
 * the generic {@link NotificationService}. Any failure is logged and swallowed; it never affects the
 * triggering operation. Follows the {@code RoomsImportNotificationEmitter} pattern.
 */
@Slf4j
@Component
public class DashboardNotificationEmitter {

    static final String TYPE_AI_FAILED = "notification.dashboard.ai.failed";
    static final String TYPE_WIDGET_DEACTIVATED = "notification.dashboard.widget.deactivated";

    static final String LINK_WIDGETS = "/dashboard-widgets";
    static final String LINK_DASHBOARD = "/";

    /** Fan-out page size (design "Notifications"). */
    static final int CHUNK_SIZE = 200;

    private final NotificationService notificationService;

    @PersistenceContext
    private EntityManager entityManager;

    public DashboardNotificationEmitter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** {@code notification.dashboard.ai.failed}, body = failure reason code (R17.1). */
    public void emitAiFailed(Long adminUserId, String reason) {
        if (adminUserId == null) {
            log.debug("Dashboard notification {} skipped: no recipient", TYPE_AI_FAILED);
            return;
        }
        send(adminUserId, TYPE_AI_FAILED, reason, LINK_WIDGETS);
    }

    /**
     * R17.2: one notification per distinct user having a placement of the deactivated widget; body =
     * widget {@code name_ru}. Users are read in keyset-ordered chunks of {@value #CHUNK_SIZE}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWidgetDeactivated(DashboardWidgetDeactivatedEvent event) {
        if (event == null || event.widgetId() == null) {
            return;
        }
        try {
            emitWidgetDeactivated(event.widgetId());
        } catch (Exception e) {
            log.warn("Dashboard notification {} for widget {} failed: {}", TYPE_WIDGET_DEACTIVATED,
                    event.widgetId(), e.toString());
        }
    }

    void emitWidgetDeactivated(Long widgetId) {
        DashboardWidgetEntity widget = entityManager.find(DashboardWidgetEntity.class, widgetId);
        if (widget == null) {
            log.debug("Dashboard notification {} skipped: widget {} not found", TYPE_WIDGET_DEACTIVATED,
                    widgetId);
            return;
        }
        String name = widget.getNameRu();
        long lastUserId = 0L;
        while (true) {
            List<Long> chunk = entityManager.createQuery(
                    "SELECT DISTINCT p.user.id FROM UserDashboardWidgetEntity p "
                            + "WHERE p.widget.id = :widgetId AND p.user.id > :lastUserId ORDER BY p.user.id",
                    Long.class)
                    .setParameter("widgetId", widgetId)
                    .setParameter("lastUserId", lastUserId)
                    .setMaxResults(CHUNK_SIZE)
                    .getResultList();
            if (chunk.isEmpty()) {
                return;
            }
            for (Long userId : chunk) {
                send(userId, TYPE_WIDGET_DEACTIVATED, name, LINK_DASHBOARD);
            }
            if (chunk.size() < CHUNK_SIZE) {
                return;
            }
            lastUserId = chunk.get(chunk.size() - 1);
        }
    }

    private void send(Long recipientId, String type, String body, String deepLink) {
        try {
            notificationService.create(recipientId, type, body, deepLink);
        } catch (Exception e) {
            log.warn("Dashboard notification {} for user {} failed: {}", type, recipientId, e.toString());
        }
    }

    void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }
}
