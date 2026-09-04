package com.foremen.service.mail;

import com.foremen.dao.model.UserEntity;

/**
 * Abstraction over the OTP-email transport used by the passwordless client-login flow
 * (FOR-03-05, Requirement 4.7, 9.2, 9.3).
 *
 * <p>Kept separate from {@link MailSender} (password-reset) and {@link InvitationMailSender}
 * (invite flow) so the OTP concerns stay isolated; all three abstractions are backed by the same
 * {@code JavaMailSender} bean and the shared Thymeleaf {@code TemplateEngine} established in
 * FOR-03-02.
 *
 * <p>The concrete implementation resolves the recipient locale from {@link UserEntity#getLocale()}
 * (RU/PL with PL fallback), renders the {@code mail/otp-code} template with the supplied code and
 * its 15-minute validity notice, and dispatches an HTML message.
 */
public interface OtpMailSender {

    /**
     * Send a one-time login code to the given client user (Requirement 4.7).
     *
     * @param user the recipient user; the email address and locale are read from this entity
     * @param code the 6-digit OTP code to render in the email body
     */
    void send(UserEntity user, String code);
}
