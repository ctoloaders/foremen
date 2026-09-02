package com.foremen.service.mail;

import com.foremen.dao.model.UserEntity;

/**
 * Abstraction over the invitation-email transport used by the invite flow (FOR-03-02).
 *
 * <p>Kept separate from {@link MailSender} (whose single method sends the plain-text
 * password-reset message) so the HTML/Thymeleaf invitation concerns stay isolated and the
 * FOR-03-01 reset path is untouched. Both abstractions are backed by the same
 * {@code JavaMailSender} bean.
 *
 * <p>Two variants are dispatched depending on the recipient's role (Requirement 4.1, 4.2):
 * an employee receives a set-password invitation carrying an activation link, while a client
 * receives an OTP-portal invitation with no set-password link.
 */
public interface InvitationMailSender {

    /**
     * Send a Set_Password_Invitation to an employee user (Requirement 4.1, 4.3).
     *
     * @param user       the recipient user; the email address and locale are read from this entity
     * @param inviteLink the fully-qualified set-password link embedded in the email body
     */
    void sendSetPasswordInvitation(UserEntity user, String inviteLink);

    /**
     * Send a Client_Portal_Invitation to a client user (Requirement 4.2, 4.4).
     *
     * <p>The body instructs the recipient to log in via an emailed OTP code and contains no
     * set-password link.
     *
     * @param user the recipient user; the email address and locale are read from this entity
     */
    void sendClientPortalInvitation(UserEntity user);
}
