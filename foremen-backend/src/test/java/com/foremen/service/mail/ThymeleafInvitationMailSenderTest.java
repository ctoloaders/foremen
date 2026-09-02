package com.foremen.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
 * Example tests for {@link ThymeleafInvitationMailSender} (task 6.2).
 *
 * <p>The sender is exercised against a <em>real</em> Thymeleaf {@link TemplateEngine} (so the
 * {@code templates/mail/*.html} files actually render) and a <em>real</em> {@link MessageResolver}
 * backed by the classpath {@code messages*.properties} bundles, while the {@link JavaMailSender} is
 * a Mockito mock whose produced {@link MimeMessage} is captured and inspected.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Both templates render for PL and RU recipients (Requirement 4.5).</li>
 *   <li>The set-password body contains the invite link (Requirement 4.3).</li>
 *   <li>The client-portal body contains no set-password link (Requirement 4.4).</li>
 *   <li>Dispatch goes through the injected {@link JavaMailSender} (Requirement 4.6).</li>
 * </ul>
 */
class ThymeleafInvitationMailSenderTest {

    private static final String INVITE_LINK =
            "http://localhost:3000/auth/set-password?token=abc-123-def-456";

    private JavaMailSender javaMailSender;
    private ThymeleafInvitationMailSender sender;

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

        sender = new ThymeleafInvitationMailSender(
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

    // --- Requirement 4.5 + 4.3: set-password template renders and carries the invite link ---

    @Test
    @DisplayName("set-password invitation renders (PL) and body contains the invite link")
    void setPasswordRendersPlWithInviteLink() {
        sender.sendSetPasswordInvitation(user("pl-employee@example.com", "PL"), INVITE_LINK);

        MimeMessage message = captureSentMessage();
        String body = htmlBody(message);

        assertThat(body).isNotBlank();
        // Polish subject/body text was resolved from the PL bundle and rendered.
        assertThat(body).contains("ustaw hasło");
        // Requirement 4.3: the exact invite link appears in the body.
        assertThat(body).contains(INVITE_LINK);
    }

    @Test
    @DisplayName("set-password invitation renders (RU) and body contains the invite link")
    void setPasswordRendersRuWithInviteLink() {
        sender.sendSetPasswordInvitation(user("ru-employee@example.com", "RU"), INVITE_LINK);

        MimeMessage message = captureSentMessage();
        String body = htmlBody(message);

        assertThat(body).isNotBlank();
        // Russian body text resolved from the RU bundle.
        assertThat(body).contains("установите пароль");
        assertThat(body).contains(INVITE_LINK);
    }

    // --- Requirement 4.5 + 4.4: client-portal template renders and has no set-password link ---

    @Test
    @DisplayName("client-portal invitation renders (PL) and body contains no set-password link")
    void clientPortalRendersPlWithoutInviteLink() {
        sender.sendClientPortalInvitation(user("pl-client@example.com", "PL"));

        MimeMessage message = captureSentMessage();
        String body = htmlBody(message);

        assertThat(body).isNotBlank();
        assertThat(body).contains("portalu klienta");
        // Requirement 4.4: no set-password link anywhere in the body.
        assertThat(body).doesNotContain("token=");
        assertThat(body).doesNotContain("set-password");
        assertThat(body).doesNotContain("<a ");
    }

    @Test
    @DisplayName("client-portal invitation renders (RU) and body contains no set-password link")
    void clientPortalRendersRuWithoutInviteLink() {
        sender.sendClientPortalInvitation(user("ru-client@example.com", "RU"));

        MimeMessage message = captureSentMessage();
        String body = htmlBody(message);

        assertThat(body).isNotBlank();
        assertThat(body).contains("клиентский портал");
        assertThat(body).doesNotContain("token=");
        assertThat(body).doesNotContain("set-password");
        assertThat(body).doesNotContain("<a ");
    }

    // --- Requirement 4.6: dispatch goes through the injected JavaMailSender ---

    @Test
    @DisplayName("both invitation variants dispatch through the injected JavaMailSender")
    void dispatchGoesThroughJavaMailSender() {
        sender.sendSetPasswordInvitation(user("emp@example.com", "PL"), INVITE_LINK);
        sender.sendClientPortalInvitation(user("client@example.com", "PL"));

        // Requirement 4.6: each send() call flowed through the mock transport.
        verify(javaMailSender, times(2)).send(any(MimeMessage.class));
    }
}
