package com.foremen.service.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Asynchronous consumer of {@link InvitationEmailEvent} that performs the actual invitation-email
 * dispatch (FOR-03-02).
 *
 * <p>The listener fires on {@link TransactionPhase#AFTER_COMMIT}, so email is sent only after the
 * invite token has been durably committed, and runs on the dedicated {@code mailTaskExecutor}
 * thread pool so dispatch never blocks the request thread. {@code fallbackExecution = true} lets
 * the listener also run when there is no active transaction (e.g. unit/property tests that invoke
 * the publisher outside a {@code @Transactional} boundary).
 *
 * <p>Because the transaction has already committed by the time this runs, any mail-transport
 * failure is logged and swallowed &mdash; it must never propagate or affect the completed user
 * creation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvitationEmailDispatcher {

    private final InvitationMailSender invitationMailSender;

    /**
     * Dispatches the role-dependent invitation email described by the event, best effort.
     *
     * <p>A {@code CLIENT} event triggers the client-portal invitation; any other event triggers the
     * set-password invitation carrying the pre-built invite link. Any exception is logged with the
     * recipient email and swallowed so a mail failure never propagates (the transaction that issued
     * the token has already committed).
     *
     * @param event the invitation-email event published by {@code InviteService}
     */
    @Async("mailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInvitationEmail(InvitationEmailEvent event) {
        try {
            if (event.client()) {
                invitationMailSender.sendClientPortalInvitation(event.user());
            } else {
                invitationMailSender.sendSetPasswordInvitation(event.user(), event.inviteLink());
            }
        } catch (Exception e) {
            String recipient = event.user() != null ? event.user().getEmail() : null;
            log.error("Failed to dispatch invitation email to {}", recipient, e);
        }
    }
}
