package com.foremen.service.procurement;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-07-03 (Requirement 12.2) - notifies the uploader when finance assigns their document to a
 * project ({@code notification.procurement.relinked}).
 *
 * <p>Delivery goes through the generic {@link NotificationService#create}, which runs in its own
 * {@code REQUIRES_NEW} transaction; emission is best-effort (failure logged and swallowed, never
 * failing the relink). Skipped when the actor is the uploader or the document has no uploader.
 */
@Slf4j
@Component
public class ProcurementRelinkNotificationEmitter {

    /** {@code Notification_Type} i18n key: a document was assigned to a project (R12.2). */
    static final String TYPE_RELINKED = "notification.procurement.relinked";

    public static final String PROCUREMENT_TAB = "procurement";

    private final NotificationService notificationService;

    public ProcurementRelinkNotificationEmitter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Emits after the relink transaction commits (or immediately when there is none). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRelinked(ProcurementRelinkedEvent event) {
        emit(event);
    }

    /** Best-effort emit; never throws. */
    public void emit(ProcurementRelinkedEvent event) {
        if (event == null || event.uploaderUserId() == null
                || Objects.equals(event.uploaderUserId(), event.actorUserId())) {
            return;
        }
        try {
            notificationService.create(event.uploaderUserId(), TYPE_RELINKED, body(event), deepLink(event));
        } catch (Exception e) {
            log.error("Failed to emit procurement relink notification for document {} to user {}",
                    event.documentId(), event.uploaderUserId(), e);
        }
    }

    /** {@code "{supplier|invoiceNumber|#id} -> {project}"}; supplier falls back to invoice, then "#id". */
    static String body(ProcurementRelinkedEvent event) {
        String label = firstNonBlank(event.supplier(), event.invoiceNumber(), "#" + event.documentId());
        String project = event.projectName() == null ? "" : event.projectName().trim();
        return project.isEmpty() ? label : label + " -> " + project;
    }

    static String deepLink(ProcurementRelinkedEvent event) {
        if (event.projectId() == null) {
            return null;
        }
        String base = "/projects/" + event.projectId() + "/" + PROCUREMENT_TAB;
        return event.documentId() == null ? base : base + "?doc=" + event.documentId();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
