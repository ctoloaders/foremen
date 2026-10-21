package com.foremen.service.clientmessage;

import com.foremen.dao.model.AuthorSide;

/**
 * FOR-06-12 (Requirement 7; design §Notifications) — an immutable application event carrying a
 * newly posted client-communication message that should raise an in-app notification. Published by
 * {@code ClientMessageService.post} on the committed post and consumed by
 * {@code ClientMessageNotificationEmitter} (task 7.1) on an {@code AFTER_COMMIT}
 * {@code @TransactionalEventListener(fallbackExecution = true)}, so emission runs only <em>after</em>
 * the post has durably committed and a failure while emitting can never roll it back (R7.4),
 * mirroring {@link com.foremen.service.WorkReportNotificationEvent}.
 *
 * <p>Following the repo's event convention, the event carries only ids / the derived side (never a
 * loaded entity), so it stays valid on the after-commit thread. The <b>recipient semantics</b> are a
 * function of {@link #authorSide} and resolved by the emitter from {@code ProjectMemberDao}:
 * <ul>
 *   <li>{@link AuthorSide#CLIENT} (a client question) ⇒ notify every ACTIVE MANAGER and every ACTIVE
 *       FOREMAN member of the project, type {@code notification.clientMessage.fromClient} (R7.1);</li>
 *   <li>{@link AuthorSide#STAFF} (a manager reply) ⇒ notify the project's CLIENT member(s), type
 *       {@code notification.clientMessage.fromStaff} (R7.2).</li>
 * </ul>
 * No recipient of the relevant kind ⇒ notify nobody, not an error (R7.3). The deep-link target is
 * {@code /projects/{projectId}/clientCommunication?message={messageId}}.
 *
 * @param projectId  the owning project id (the Komunikacja deep-link is scoped to it, R7.1)
 * @param messageId  the posted message id (the deep-link {@code message} target)
 * @param authorSide the posted message's side ({@code CLIENT}/{@code STAFF}), selecting the
 *                   notification type + recipient set
 */
public record ClientMessageNotificationEvent(
        Long projectId,
        Long messageId,
        AuthorSide authorSide) {
}
