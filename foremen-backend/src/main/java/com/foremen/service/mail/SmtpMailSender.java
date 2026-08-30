package com.foremen.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * {@link MailSender} implementation backed by Spring's {@link JavaMailSender}.
 * <p>
 * Configured for Gmail SMTP ({@code smtp.gmail.com:587}, STARTTLS) via {@code spring.mail.*}
 * properties. Credentials (a Gmail account and a Gmail App Password) and the sender/reset-link
 * settings are supplied through environment variables and are never committed to the repository.
 */
@Slf4j
@Component
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final String from;
    private final String resetBaseUrl;

    public SmtpMailSender(JavaMailSender javaMailSender,
                          @Value("${foremen.mail.from:}") String from,
                          @Value("${foremen.mail.reset-base-url:http://localhost:3000/auth/set-password}") String resetBaseUrl) {
        this.javaMailSender = javaMailSender;
        this.from = from;
        this.resetBaseUrl = resetBaseUrl;
    }

    @Override
    public void sendPasswordReset(String toEmail, String token) {
        String resetLink = resetBaseUrl + token;

        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(toEmail);
        message.setSubject("Password reset");
        message.setText("To reset your password, follow this link: " + resetLink);

        javaMailSender.send(message);
        log.info("Password-reset email dispatched to {}", toEmail);
    }
}
