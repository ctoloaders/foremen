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
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * {@link OtpMailSender} implementation that renders the HTML OTP-code body with the shared
 * Thymeleaf {@link TemplateEngine} and dispatches it through Spring's {@link JavaMailSender} using
 * a {@link MimeMessageHelper} in HTML mode (Requirement 4.7).
 *
 * <p>The recipient locale is resolved from {@link UserEntity#getLocale()} via
 * {@link EmailLocaleResolver} — {@code RU} case-insensitively yields Russian, everything else
 * (including {@code null}/blank/unsupported) falls back to Polish (Requirement 9.3). The email
 * subject and body text are looked up through the existing {@link MessageResolver} in that
 * resolved locale (Requirement 9.2); since Polish is the {@code messages.properties} base, any
 * missing RU key falls back to PL automatically. The body message ({@code mail.otp.body}) carries
 * the code as parameter {@code {0}} and its 15-minute validity notice.
 */
@Slf4j
@Component
public class ThymeleafOtpMailSender implements OtpMailSender {

    private static final String OTP_SUBJECT = "mail.otp.subject";
    private static final String OTP_BODY = "mail.otp.body";

    private static final String OTP_TEMPLATE = "mail/otp-code";

    private final JavaMailSender javaMailSender;
    private final TemplateEngine templateEngine;
    private final MessageResolver messageResolver;
    private final String from;

    public ThymeleafOtpMailSender(JavaMailSender javaMailSender,
                                  TemplateEngine templateEngine,
                                  MessageResolver messageResolver,
                                  @Value("${foremen.mail.from:}") String from) {
        this.javaMailSender = javaMailSender;
        this.templateEngine = templateEngine;
        this.messageResolver = messageResolver;
        this.from = from;
    }

    @Override
    public void send(UserEntity user, String code) {
        Locale locale = EmailLocaleResolver.resolve(user.getLocale());

        String subject = messageResolver.resolve(OTP_SUBJECT, null, locale);
        // The body message embeds the code as {0} and states the 15-minute validity (Req 9.2).
        String bodyText = messageResolver.resolve(OTP_BODY, new Object[] {code}, locale);

        Context context = new Context(locale);
        context.setVariable("subject", subject);
        context.setVariable("bodyText", bodyText);
        context.setVariable("code", code);

        String html = templateEngine.process(OTP_TEMPLATE, context);
        send(user.getEmail(), subject, html);
        log.info("OTP login code email dispatched to {}", user.getEmail());
    }

    /**
     * Builds and sends an HTML message through {@link JavaMailSender}. Any messaging failure is
     * propagated (wrapped in a runtime exception) so the caller's transaction rolls back.
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
            throw new IllegalStateException("Failed to dispatch OTP email to " + toEmail, e);
        }
    }
}
