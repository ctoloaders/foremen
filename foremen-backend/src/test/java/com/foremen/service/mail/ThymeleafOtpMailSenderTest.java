package com.foremen.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.dao.model.UserEntity;

import jakarta.mail.internet.MimeMessage;

/**
 * Example test for {@link ThymeleafOtpMailSender} (task 9.3).
 *
 * <p>The sender is exercised against a <em>real</em> Thymeleaf {@link TemplateEngine} (so the
 * production {@code templates/mail/otp-code.html} actually renders) and a <em>real</em>
 * {@link MessageResolver} backed by the classpath {@code messages*.properties} bundles, while the
 * {@link JavaMailSender} is a Mockito mock whose produced {@link MimeMessage} is captured and
 * inspected — mirroring {@link ThymeleafInvitationMailSenderTest}.
 *
 * <p>Coverage (Requirement 4.7): the OTP template renders the 6-digit code and the 15-minute
 * validity notice for both PL and RU recipients, with the code appearing verbatim (leading zeros
 * preserved) in the rendered body.
 */
class ThymeleafOtpMailSenderTest {

    private JavaMailSender javaMailSender;
    private ThymeleafOtpMailSender sender;

    @BeforeEach
    void setUp() {
        // Real Thymeleaf engine resolving the production templates from the classpath.
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        // SpringTemplateEngine uses the SpEL expression evaluator (matching the production
        // auto-configured engine) so no OGNL runtime dependency is required.
        TemplateEngine templateEngine = new SpringTemplateEngine();
        ((SpringTemplateEngine) templateEngine).setTemplateResolver(resolver);

        // Real message resolver backed by the actual PL/RU bundles.
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        MessageResolver messageResolver = new MessageResolver(messageSource);

        // Mock transport: createMimeMessage() must return a usable MimeMessage so the
        // MimeMessageHelper can populate it; a lightweight JavaMailSenderImpl builds one.
        javaMailSender = mock(JavaMailSender.class);
        JavaMailSenderImpl real = new JavaMailSenderImpl();
        real.setJavaMailProperties(new Properties());
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> real.createMimeMessage());

        sender = new ThymeleafOtpMailSender(
                javaMailSender, templateEngine, messageResolver, "noreply@foremen.test");
    }

    private static UserEntity user(String email, String locale) {
        UserEntity user = new UserEntity();
        user.setName("Recipient");
        user.setEmail(email);
        user.setLocale(locale);
        return user;
    }

    /** Extracts the rendered HTML body from the captured {@link MimeMessage}. */
    private static String htmlBody(MimeMessage message) {
        try {
            Object content = message.getContent();
            return String.valueOf(content);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read MimeMessage content", e);
        }
    }

    private MimeMessage captureSentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }

    // --- Requirement 4.7: OTP template renders the code and 15-minute validity notice (PL) ---

    @Test
    @DisplayName("otp-code template renders the code and validity notice (PL)")
    void otpRendersPlWithCodeAndValidityNotice() {
        // Leading zero preserved to confirm the code appears verbatim.
        String code = "007413";
        sender.send(user("pl-client@example.com", "PL"), code);

        MimeMessage message = captureSentMessage();
        String body = htmlBody(message);

        assertThat(body).isNotBlank();
        // The 6-digit code renders verbatim (leading zero preserved).
        assertThat(body).contains(code);
        // Polish body text resolved from the PL bundle, including the 15-minute validity notice.
        assertThat(body).contains("kod logowania");
        assertThat(body).contains("ważny przez 15 minut");
    }

    // --- Requirement 4.7: OTP template renders the code and 15-minute validity notice (RU) ---

    @Test
    @DisplayName("otp-code template renders the code and validity notice (RU)")
    void otpRendersRuWithCodeAndValidityNotice() {
        // Numeric OTP-shaped value with a leading zero preserved.
        String code = "042900";
        sender.send(user("ru-client@example.com", "RU"), code);

        MimeMessage message = captureSentMessage();
        String body = htmlBody(message);

        assertThat(body).isNotBlank();
        assertThat(body).contains(code);
        // Russian body text resolved from the RU bundle, including the 15-minute validity notice.
        assertThat(body).contains("код для входа");
        assertThat(body).contains("действителен в течение 15 минут");
    }

    // --- Requirement 4.7: dispatch goes through the injected JavaMailSender ---

    @Test
    @DisplayName("otp email dispatches through the injected JavaMailSender")
    void dispatchGoesThroughJavaMailSender() {
        sender.send(user("client@example.com", "PL"), "123456");

        verify(javaMailSender).send(any(MimeMessage.class));
    }
}
