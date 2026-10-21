package com.foremen.service.clientmessage;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.AuthorSide;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-12 (Requirement 7; design §Notifications): the best-effort consumer that turns a
 * {@link ClientMessageNotificationEvent} into one or more in-app {@code Notification}s via the
 * generic FOR-05-07 {@link NotificationService} (R13 of FOR-05-07). Like
 * {@link com.foremen.service.WorkReportNotificationEmitter}, this module persists / delivers nothing
 * itself — it is purely a <b>consumer</b> of the generic notification extension point and adds no
 * new delivery channel.
 *
 * <p>The recipient set and the notification type are a function of the posted message's
 * {@link AuthorSide}, each notification carrying a <b>deep-link</b> to the message in the project's
 * Komunikacja tab ({@code /projects/{projectId}/clientCommunication?message={messageId}}, R7.1):
 *
 * <table>
 *   <caption>Requirement 7 notification map</caption>
 *   <tr><th>Trigger</th><th>Type (i18n key)</th><th>Recipient(s)</th></tr>
 *   <tr><td>{@link AuthorSide#CLIENT} (client question)</td><td>{@link #TYPE_FROM_CLIENT}</td>
 *       <td>every ACTIVE MANAGER and every ACTIVE FOREMAN member of the project (R7.1)</td></tr>
 *   <tr><td>{@link AuthorSide#STAFF} (manager reply)</td><td>{@link #TYPE_FROM_STAFF}</td>
 *       <td>the project's ACTIVE CLIENT member(s) (R7.2)</td></tr>
 * </table>
 *
 * <p>No recipient of the relevant kind ⇒ notify nobody, which is a no-op and <b>not</b> an error
 * (R7.3).
 *
 * <h2>Best-effort emission (R7.4, R7.5)</h2>
 * The listener fires on {@link TransactionPhase#AFTER_COMMIT}, so a notification is created only
 * <em>after</em> the post has durably committed — a failure here therefore cannot roll it back
 * (R7.4). {@code fallbackExecution = true} lets the listener also run when there is no active
 * transaction (e.g. unit tests that publish outside a {@code @Transactional} boundary). Every
 * recipient is wrapped individually so <b>any</b> exception (a missing recipient, a
 * {@code NotificationService} failure, …) is logged and swallowed — emission never propagates and
 * never blocks the committed post, and one failed recipient never starves the others (R7.5),
 * mirroring {@link com.foremen.service.WorkReportNotificationEmitter}.
 *
 * <p>The {@code type} values are pure {@code Notification_Type} i18n keys resolved to a localized
 * title on the frontend; the PL/RU title wiring for them is task 11.1.
 */
@Component
@Slf4j
public class ClientMessageNotificationEmitter {

    /**
     * {@code Notification_Type} i18n key: a client posted a question — delivered to each ACTIVE
     * MANAGER and each ACTIVE FOREMAN of the project (R7.1).
     */
    static final String TYPE_FROM_CLIENT = "notification.clientMessage.fromClient";

    /**
     * {@code Notification_Type} i18n key: staff (the MANAGER) replied — delivered to the project's
     * ACTIVE CLIENT member(s) (R7.2).
     */
    static final String TYPE_FROM_STAFF = "notification.clientMessage.fromStaff";

    /** Project role codes notified on a client question (R7.1). */
    private static final String MANAGER_ROLE = "MANAGER";
    private static final String FOREMAN_ROLE = "FOREMAN";
    private static final Set<String> STAFF_RECIPIENT_ROLES = Set.of(MANAGER_ROLE, FOREMAN_ROLE);

    /** Project role code notified on a staff reply (R7.2). */
    private static final String CLIENT_ROLE = "CLIENT";

    /** The Komunikacja tab the deep-link targets ({@code /projects/{projectId}/clientCommunication}, R7.1). */
    private static final String CLIENT_COMMUNICATION_TAB = "clientCommunication";

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;

    public ClientMessageNotificationEmitter(NotificationService notificationService,
                                            ProjectMemberDao projectMemberDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
    }

    /**
     * Emits the in-app notification(s) for a posted client-communication message, best effort. Runs
     * after the post commits so it can never roll it back (R7.4); each recipient's emission is
     * wrapped so any failure is logged and swallowed (R7.5).
     *
     * @param event the event published by {@code ClientMessageService.post}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClientMessage(ClientMessageNotificationEvent event) {
        if (event == null || event.authorSide() == null || event.projectId() == null) {
            return;
        }
        String deepLink = deepLink(event.projectId(), event.messageId());
        switch (event.authorSide()) {
            // R7.1 — a client question notifies every ACTIVE MANAGER + ACTIVE FOREMAN of the project.
            case CLIENT -> notifyActiveMembers(event, STAFF_RECIPIENT_ROLES, TYPE_FROM_CLIENT, deepLink);
            // R7.2 — a staff reply notifies the project's ACTIVE CLIENT member(s).
            case STAFF -> notifyActiveMembers(event, Set.of(CLIENT_ROLE), TYPE_FROM_STAFF, deepLink);
        }
    }

    /**
     * Fans {@code type} out to every ACTIVE project member whose project-role code is in
     * {@code roleCodes} (R7.1 / R7.2). The recipient set is resolved here (not at publish time) from
     * {@code ProjectMemberDao}: every project member whose Assignment_Status is ACTIVE and whose
     * project role code matches. When the project has no such member, nothing is notified — a no-op,
     * still not an error (R7.3).
     */
    private void notifyActiveMembers(ClientMessageNotificationEvent event, Set<String> roleCodes,
                                     String type, String deepLink) {
        List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(event.projectId());
        for (ProjectMemberEntity member : members) {
            if (!isActiveMemberOfRole(member, roleCodes)) {
                continue;
            }
            UserEntity user = member.getUser();
            Long recipientId = user == null ? null : user.getId();
            emitTo(recipientId, type, deepLink, event);
        }
    }

    /** True iff {@code member} is an ACTIVE member whose project-role code is in {@code roleCodes}. */
    private static boolean isActiveMemberOfRole(ProjectMemberEntity member, Set<String> roleCodes) {
        if (member == null || member.getAssignmentStatus() != AssignmentStatus.ACTIVE) {
            return false;
        }
        RoleEntity role = member.getProjectRole();
        return role != null && role.getCode() != null && roleCodes.contains(role.getCode());
    }

    /** Creates one notification for a single recipient, logging and swallowing any failure (R7.5). */
    private void emitTo(Long recipientUserId, String type, String deepLink,
                        ClientMessageNotificationEvent event) {
        if (recipientUserId == null) {
            return;
        }
        try {
            notificationService.create(recipientUserId, type, null, deepLink);
        } catch (Exception e) {
            // R7.5: log-only on failure — never propagate, never block the committed post, and never
            // starve the remaining recipients.
            log.error("Failed to emit client-message notification (type {}) for message {} in "
                            + "project {} to user {} (authorSide {})",
                    type, event.messageId(), event.projectId(), recipientUserId, event.authorSide(), e);
        }
    }

    /**
     * The Komunikacja tab deep-link for a message
     * ({@code /projects/{projectId}/clientCommunication?message={messageId}}, R7.1): the
     * project-workspace route whose {@code :tab} slug is the {@code clientCommunication} tab, with the
     * message id as the {@code message} query param so the panel opens that message. Returns the
     * base tab link when the message id is unknown.
     */
    private static String deepLink(Long projectId, Long messageId) {
        String base = "/projects/" + projectId + "/" + CLIENT_COMMUNICATION_TAB;
        return messageId == null ? base : base + "?message=" + messageId;
    }
}
