package com.foremen.service.mail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.EmailLocaleResolver;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link InvitationMailSender} implementation that renders HTML invitation bodies with a Thymeleaf
 * {@link TemplateEngine} and dispatches them through Spring's {@link JavaMailSender} using a
 * {@link MimeMessageHelper} in HTML mode (Requirement 4.5, 4.6).
 *
 * <p>The recipient locale is resolved from {@link UserEntity#getLocale()} via
 * {@link EmailLocaleResolver} — {@code RU} case-insensitively yields Russian, everything else
 * (including {@code null}/blank/unsupported) falls back to Polish (Requirement 4.7, 8.4). The
 * email subject and body text are looked up through the existing {@link MessageResolver} in that
 * resolved locale (Requirement 8.2, 8.4); since Polish is the {@code messages.properties} base,
 * any missing RU key falls back to PL automatically.
 */
@Slf4j
@Component
public class ThymeleafInvitationMailSender implements InvitationMailSender {

    private static final String SET_PASSWORD_SUBJECT = "mail.invite.set-password.subject";
    private static final String SET_PASSWORD_BODY = "mail.invite.set-password.body";
    private static final String CLIENT_PORTAL_SUBJECT = "mail.invite.client-portal.subject";
    private static final String CLIENT_PORTAL_BODY = "mail.invite.client-portal.body";

    private static final String SET_PASSWORD_TEMPLATE = "mail/invite-set-password";
    private static final String CLIENT_PORTAL_TEMPLATE = "mail/invite-client-portal";

    private final JavaMailSender javaMailSender;
    private final TemplateEngine templateEngine;
    private final MessageResolver messageResolver;
    private final String from;

    public ThymeleafInvitationMailSender(JavaMailSender javaMailSender,
                                         TemplateEngine templateEngine,
                                         MessageResolver messageResolver,
                                         @Value("${foremen.mail.from:}") String from) {
        this.javaMailSender = javaMailSender;
        this.templateEngine = templateEngine;
        this.messageResolver = messageResolver;
        this.from = from;
    }

    @Override
    public void sendSetPasswordInvitation(UserEntity user, String inviteLink) {
        Locale locale = EmailLocaleResolver.resolve(user.getLocale());

        String subject = messageResolver.resolve(SET_PASSWORD_SUBJECT, null, locale);
        String bodyText = messageResolver.resolve(SET_PASSWORD_BODY, null, locale);

        Context context = new Context(locale);
        context.setVariable("subject", subject);
        context.setVariable("bodyText", bodyText);
        context.setVariable("inviteLink", inviteLink);

        String html = templateEngine.process(SET_PASSWORD_TEMPLATE, context);
        send(user.getEmail(), subject, html);
        log.info("Set-password invitation email dispatched to {}", user.getEmail());
    }

    @Override
    public void sendClientPortalInvitation(UserEntity user) {
        Locale locale = EmailLocaleResolver.resolve(user.getLocale());

        String subject = messageResolver.resolve(CLIENT_PORTAL_SUBJECT, null, locale);
        String bodyText = messageResolver.resolve(CLIENT_PORTAL_BODY, null, locale);

        Context context = new Context(locale);
        context.setVariable("subject", subject);
        context.setVariable("bodyText", bodyText);

        String html = templateEngine.process(CLIENT_PORTAL_TEMPLATE, context);
        send(user.getEmail(), subject, html);
        log.info("Client-portal invitation email dispatched to {}", user.getEmail());
    }

    /**
     * Builds and sends an HTML message through {@link JavaMailSender}. Any messaging failure is
     * propagated (wrapped in a runtime exception) so the caller's transaction rolls back
     * (Requirement 4.8).
     */
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
            throw new IllegalStateException("Failed to dispatch invitation email to " + toEmail, e);
        }
    }
}
