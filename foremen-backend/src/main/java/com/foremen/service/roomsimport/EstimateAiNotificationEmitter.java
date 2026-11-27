package com.foremen.service.roomsimport;

import org.springframework.stereotype.Component;

import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-04 (Requirement 16, design B9) — best-effort in-app notification for a finished
 * {@code ESTIMATE} job, created through the generic {@link NotificationService} for the user who
 * started the job. Mirrors {@link RoomsImportNotificationEmitter}: failures are logged and swallowed.
 * Callers must invoke it only after winning the status-conditional terminal update (see
 * {@link RoomsImportJobTerminator}), never for cancelled jobs and never for {@code ROOMS_ONLY} jobs.
 */
@Slf4j
@Component
public class EstimateAiNotificationEmitter {

    static final String TYPE_COMPLETED = "notification.estimateAi.completed";
    static final String TYPE_FAILED = "notification.estimateAi.failed";

    private final NotificationService notificationService;

    public EstimateAiNotificationEmitter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** {@code notification.estimateAi.completed}, body {@code null}, link to the Estimate tab (R16.1, 24.9). */
    public void emitCompleted(Long projectId, Long createdById) {
        emit(projectId, createdById, TYPE_COMPLETED, null, completedLink(projectId));
    }

    /** {@code notification.estimateAi.failed}, body = the failure reason code, link to rooms (R16.2). */
    public void emitFailed(Long projectId, Long createdById, String reason) {
        emit(projectId, createdById, TYPE_FAILED, reason, failedLink(projectId));
    }

    static String completedLink(Long projectId) {
        return "/projects/" + projectId + "/estimate";
    }

    static String failedLink(Long projectId) {
        return "/projects/" + projectId + "/rooms";
    }

    private void emit(Long projectId, Long recipientId, String type, String body, String link) {
        if (recipientId == null || projectId == null) {
            log.debug("Estimate AI notification {} skipped: no recipient/project", type);
            return;
        }
        try {
            notificationService.create(recipientId, type, body, link);
        } catch (Exception e) {
            log.warn("Estimate AI notification {} for user {} failed: {}", type, recipientId, e.toString());
        }
    }
}
