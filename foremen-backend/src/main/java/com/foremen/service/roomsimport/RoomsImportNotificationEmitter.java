package com.foremen.service.roomsimport;

import org.springframework.stereotype.Component;

import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (Requirement 17) — best-effort in-app notification for a finished autoimport job, created
 * through the generic {@link NotificationService} for the user who started the job. Any failure
 * (missing recipient, notification service error, ...) is logged and swallowed; it never changes the
 * job's terminal state. Callers must invoke it only after winning the status-conditional terminal
 * update (see {@link RoomsImportJobTerminator}).
 */
@Slf4j
@Component
public class RoomsImportNotificationEmitter {

    static final String TYPE_COMPLETED = "notification.roomsImport.completed";
    static final String TYPE_FAILED = "notification.roomsImport.failed";

    private final NotificationService notificationService;

    public RoomsImportNotificationEmitter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** {@code notification.roomsImport.completed}, body {@code null} (R17.1, R17.3, R17.4). */
    public void emitCompleted(Long projectId, Long createdById) {
        emit(projectId, createdById, TYPE_COMPLETED, null);
    }

    /** {@code notification.roomsImport.failed}, body = the failure reason code (R17.1a). */
    public void emitFailed(Long projectId, Long createdById, String reason) {
        emit(projectId, createdById, TYPE_FAILED, reason);
    }

    static String deepLink(Long projectId) {
        return "/projects/" + projectId + "/rooms";
    }

    private void emit(Long projectId, Long recipientId, String type, String body) {
        if (recipientId == null || projectId == null) {
            log.debug("Rooms import notification {} skipped: no recipient/project", type);
            return;
        }
        try {
            notificationService.create(recipientId, type, body, deepLink(projectId));
        } catch (Exception e) {
            log.warn("Rooms import notification {} for user {} failed: {}", type, recipientId, e.toString());
        }
    }
}
