package com.foremen.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-01 (Requirement 9; design §4.4): the best-effort consumer that turns a
 * {@link WorkReportNotificationEvent} into one or more in-app {@code Notification}s via the generic
 * FOR-05-07 {@link NotificationService} (R13 of FOR-05-07). This module persists / delivers nothing
 * itself — it is purely a <b>consumer</b> of the generic notification extension point, adding no new
 * delivery channel.
 *
 * <p>It wires the four Requirement 9 triggers, each notification carrying a <b>deep-link</b> to the
 * report in the project's Work Reporting tab
 * ({@code /projects/{projectId}/workReporting?report={reportId}}, R9.1):
 *
 * <table>
 *   <caption>Requirement 9 notification map</caption>
 *   <tr><th>Trigger</th><th>Type (i18n key)</th><th>Recipient(s)</th></tr>
 *   <tr><td>{@code SUBMITTED} (non-FOREMAN submit)</td><td>{@link #TYPE_SUBMITTED}</td><td>every ACTIVE FOREMAN member of the project (R9.1)</td></tr>
 *   <tr><td>{@code APPROVED}</td><td>{@link #TYPE_APPROVED}</td><td>the report author (R9.2)</td></tr>
 *   <tr><td>{@code REJECTED}</td><td>{@link #TYPE_REJECTED}</td><td>the report author, body = reason (R9.3)</td></tr>
 *   <tr><td>{@code CHANGES_REQUESTED}</td><td>{@link #TYPE_CHANGES_REQUESTED}</td><td>the report author, body = reason (R9.4)</td></tr>
 * </table>
 *
 * <p>The FOREMAN self-report fast path (R5) auto-approves on create and emits nothing: the service
 * publishes <b>no</b> event for it, so this emitter inherently stays silent (R9.5).
 *
 * <h2>Best-effort emission (R9)</h2>
 * The listener fires on {@link TransactionPhase#AFTER_COMMIT}, so a notification is created only
 * <em>after</em> the work-report transition has durably committed — a failure here therefore cannot
 * roll it back. {@code fallbackExecution = true} lets the listener also run when there is no active
 * transaction (e.g. unit tests that publish outside a {@code @Transactional} boundary). Every
 * recipient is wrapped individually so <b>any</b> exception (a missing recipient, a
 * {@code NotificationService} failure, …) is logged and swallowed — emission never propagates and
 * never blocks the triggering transition, and one failed recipient never starves the others
 * (mirroring {@code OfferNotificationEmitter} / {@code DocumentNotificationEmitter}).
 *
 * <p>The {@code type} values are pure {@code Notification_Type} i18n keys resolved to a localized
 * title on the frontend (R9.6, R12.3); no backend {@code messages} entry is required for them (the
 * PL/RU title wiring is task 7.1).
 */
@Component
@Slf4j
public class WorkReportNotificationEmitter {

    /** {@code Notification_Type} i18n key: a report was submitted (to each ACTIVE FOREMAN, R9.1). */
    static final String TYPE_SUBMITTED = "notification.workReport.submitted";

    /** {@code Notification_Type} i18n key: a report was approved (to the author, R9.2). */
    static final String TYPE_APPROVED = "notification.workReport.approved";

    /** {@code Notification_Type} i18n key: a report was rejected (to the author, + reason, R9.3). */
    static final String TYPE_REJECTED = "notification.workReport.rejected";

    /** {@code Notification_Type} i18n key: changes requested (to the author, + reason, R9.4). */
    static final String TYPE_CHANGES_REQUESTED = "notification.workReport.changesRequested";

    /** The project role code of the submit-notification recipients (the approvers, R9.1). */
    private static final String FOREMAN_ROLE = "FOREMAN";

    /** The Work Reporting tab key the deep-link targets ({@code /projects/{projectId}/workReporting}, R9.1). */
    private static final String WORK_REPORTING_TAB = "workReporting";

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;

    public WorkReportNotificationEmitter(NotificationService notificationService,
                                         ProjectMemberDao projectMemberDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
    }

    /**
     * Emits the in-app notification(s) for a work-report trigger, best effort. Runs after the
     * work-report transition commits so it can never roll it back (R9); each recipient's emission is
     * wrapped so any failure is logged and swallowed.
     *
     * @param event the work-report notification event published by {@link WorkReportService}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkReport(WorkReportNotificationEvent event) {
        if (event == null || event.trigger() == null) {
            return;
        }
        String deepLink = deepLink(event.projectId(), event.reportId());
        switch (event.trigger()) {
            case SUBMITTED -> notifyActiveForemen(event, deepLink);
            case APPROVED -> emitTo(event.authorUserId(), TYPE_APPROVED, null, deepLink, event);
            case REJECTED -> emitTo(event.authorUserId(), TYPE_REJECTED, event.reason(), deepLink, event);
            case CHANGES_REQUESTED ->
                    emitTo(event.authorUserId(), TYPE_CHANGES_REQUESTED, event.reason(), deepLink, event);
        }
    }

    /**
     * Fans the submit notification out to every ACTIVE FOREMAN member of the project (R9.1). The
     * recipient set is resolved here (not at publish time) from {@code ProjectMemberDao}: every
     * project member whose Assignment_Status is ACTIVE and whose project role code is {@code FOREMAN}.
     * If the project has no such member, nothing is notified (still not an error).
     */
    private void notifyActiveForemen(WorkReportNotificationEvent event, String deepLink) {
        Long projectId = event.projectId();
        if (projectId == null) {
            return;
        }
        List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(projectId);
        for (ProjectMemberEntity member : members) {
            if (!isActiveForeman(member)) {
                continue;
            }
            UserEntity user = member.getUser();
            Long recipientId = user == null ? null : user.getId();
            emitTo(recipientId, TYPE_SUBMITTED, null, deepLink, event);
        }
    }

    /** True iff {@code member} is an ACTIVE FOREMAN member — a submit-notification recipient (R9.1). */
    private static boolean isActiveForeman(ProjectMemberEntity member) {
        if (member == null || member.getAssignmentStatus() != AssignmentStatus.ACTIVE) {
            return false;
        }
        RoleEntity role = member.getProjectRole();
        return role != null && FOREMAN_ROLE.equals(role.getCode());
    }

    /** Creates one notification for a single recipient, logging and swallowing any failure (R9). */
    private void emitTo(Long recipientUserId, String type, String body, String deepLink,
                        WorkReportNotificationEvent event) {
        if (recipientUserId == null) {
            return;
        }
        try {
            notificationService.create(recipientUserId, type, body, deepLink);
        } catch (Exception e) {
            // R9: log-only on failure — never propagate, never block the committed work-report
            // transition, and never starve the remaining recipients.
            log.error("Failed to emit work-report notification (type {}) for report {} to user {} "
                            + "(trigger {})",
                    type, event.reportId(), recipientUserId, event.trigger(), e);
        }
    }

    /**
     * The Work Reporting tab deep-link for a report
     * ({@code /projects/{projectId}/workReporting?report={reportId}}, R9.1): the project-workspace
     * route whose {@code :tab} slug is the {@code workReporting} tab, with the report id as the
     * {@code report} query param so the panel opens that report. Returns {@code null} when the
     * project id is unknown (a non-interactive notification, consistent with
     * {@code NotificationService.create}'s nullable deep-link).
     */
    private static String deepLink(Long projectId, Long reportId) {
        if (projectId == null) {
            return null;
        }
        String base = "/projects/" + projectId + "/" + WORK_REPORTING_TAB;
        return reportId == null ? base : base + "?report=" + reportId;
    }
}
