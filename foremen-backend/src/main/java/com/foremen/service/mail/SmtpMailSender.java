package com.foremen.service.mail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.EmailLocaleResolver;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * {@link MailSender} implementation that renders the password-reset email as localized HTML with
 * Thymeleaf and sends it through Spring's {@link JavaMailSender} (FOR-03-09).
 * <p>
 * The language follows the recipient's {@code users.locale} via {@link EmailLocaleResolver}
 * ({@code RU} → Russian, otherwise Polish), the same rule used for invitation and OTP emails.
 * The link is {@code <foremen.mail.reset-base-url>?token=<url-encoded token>}.
 */
@Slf4j
@Component
public class SmtpMailSender implements MailSender {

    static final String TEMPLATE = "mail/password-reset";
    static final String SUBJECT_KEY = "mail.passwordReset.subject";
    static final String GREETING_KEY = "mail.passwordReset.greeting";
    static final String BODY_KEY = "mail.passwordReset.body";
    static final String BUTTON_KEY = "mail.passwordReset.button";
    static final String FOOTER_KEY = "mail.passwordReset.footer";

    private final JavaMailSender javaMailSender;
    private final TemplateEngine templateEngine;
    private final MessageResolver messageResolver;
    private final String from;
    private final String resetBaseUrl;

    public SmtpMailSender(JavaMailSender javaMailSender,
                          TemplateEngine templateEngine,
                          MessageResolver messageResolver,
                          @Value("${foremen.mail.from:}") String from,
                          @Value("${foremen.mail.reset-base-url:http://localhost:3000/auth/reset-password}") String resetBaseUrl) {
        this.javaMailSender = javaMailSender;
        this.templateEngine = templateEngine;
        this.messageResolver = messageResolver;
        this.from = from;
        this.resetBaseUrl = resetBaseUrl;
    }

    @Override
    public void sendPasswordReset(UserEntity user, String token) {
        Locale locale = EmailLocaleResolver.resolve(user.getLocale());
        String resetLink = buildResetLink(token);
        String displayName = user.getName() != null && !user.getName().isBlank()
                ? user.getName() : user.getEmail();

        String subject = messageResolver.resolve(SUBJECT_KEY, null, locale);
        Context context = new Context(locale);
        context.setVariable("subject", subject);
        context.setVariable("greeting", messageResolver.resolve(GREETING_KEY, new Object[]{displayName}, locale));
        context.setVariable("bodyText", messageResolver.resolve(BODY_KEY, null, locale));
        context.setVariable("buttonText", messageResolver.resolve(BUTTON_KEY, null, locale));
        context.setVariable("footerText", messageResolver.resolve(FOOTER_KEY, null, locale));
        context.setVariable("resetLink", resetLink);

        String html = templateEngine.process(TEMPLATE, context);
        send(user.getEmail(), subject, html);
        log.info("Password-reset email dispatched to {}", user.getEmail());
    }

    String buildResetLink(String token) {
        return UriComponentsBuilder.fromUriString(resetBaseUrl)
                .queryParam("token", token)
                .encode()
                .toUriString();
    }

    private void send(String toEmail, String subject, String htmlBody) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            javaMailSender.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to dispatch password-reset email to " + toEmail, e);
        }
    }
}
