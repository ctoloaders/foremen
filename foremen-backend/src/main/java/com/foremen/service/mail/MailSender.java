package com.foremen.service.mail;

import com.foremen.dao.model.UserEntity;

/**
 * Abstraction over the outbound mail transport used by the authentication flow.
 * <p>
 * The interface keeps {@code AuthService} decoupled from the concrete SMTP layer so that
 * tests can inject a mock or no-op sender instead of hitting a real mail server.
 */
public interface MailSender {

    /**
     * Send a localized password-reset message to the given user (FOR-03-09).
     *
     * @param user  the recipient; its email is the address and its locale selects the language
     * @param token the single-use password-reset token; the concrete implementation builds the
     *              reset link as {@code <reset-base-url>?token=<token>}
     */
    void sendPasswordReset(UserEntity user, String token);
}
