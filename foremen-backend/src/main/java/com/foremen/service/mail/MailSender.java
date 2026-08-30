package com.foremen.service.mail;

/**
 * Abstraction over the outbound mail transport used by the authentication flow.
 * <p>
 * The interface keeps {@code AuthService} decoupled from the concrete SMTP layer so that
 * tests can inject a mock or no-op sender instead of hitting a real mail server.
 */
public interface MailSender {

    /**
     * Send a password-reset message to the given recipient.
     *
     * @param toEmail the recipient email address
     * @param token   the single-use password-reset token; the concrete implementation
     *                builds the reset link from the configured base URL plus this token
     */
    void sendPasswordReset(String toEmail, String token);
}
