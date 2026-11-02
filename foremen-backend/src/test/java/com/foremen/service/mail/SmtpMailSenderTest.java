package com.foremen.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.dao.model.UserEntity;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * FOR-03-09: the password-reset email is localized HTML (PL/RU by {@code users.locale}) and links
 * to {@code <reset-base-url>?token=<token>}. Real Thymeleaf engine + real message bundles; the
 * transport is mocked and the produced {@link MimeMessage} is inspected.
 */
class SmtpMailSenderTest {

    private static final String BASE_URL = "https://dev.foremen.eu/auth/reset-password";

    private JavaMailSender javaMailSender;
    private SmtpMailSender sender;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setUseCodeAsDefaultMessage(true);
        MessageResolver messageResolver = new MessageResolver(messageSource);

        javaMailSender = mock(JavaMailSender.class);
        JavaMailSenderImpl real = new JavaMailSenderImpl();
        real.setJavaMailProperties(new Properties());
        when(javaMailSender.createMimeMessage()).thenAnswer(inv -> real.createMimeMessage());

        sender = new SmtpMailSender(javaMailSender, templateEngine, messageResolver,
                "noreply@foremen.test", BASE_URL);
    }

    private static UserEntity user(String email, String localeTag) {
        UserEntity user = new UserEntity();
        user.setName("Jan Kowalski");
        user.setEmail(email);
        user.setLocale(localeTag == null ? null : Locale.forLanguageTag(localeTag));
        return user;
    }

    private MimeMessage captureSentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }

    private static String body(MimeMessage message) throws Exception {
        return String.valueOf(message.getContent());
    }

    @Test
    @DisplayName("PL recipient gets a Polish subject/body and a ?token= link to the reset page")
    void polishEmail() throws Exception {
        sender.sendPasswordReset(user("pl@example.com", "PL"), "abc_DEF-123");

        MimeMessage message = captureSentMessage();
        assertThat(message.getSubject()).isEqualTo("Resetowanie hasła — Foremen");
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo("pl@example.com");
        String html = body(message);
        assertThat(html).contains("Dzień dobry, Jan Kowalski!");
        assertThat(html).contains("Ustaw nowe hasło");
        assertThat(html).contains("ważny 60 minut");
        assertThat(html).contains(BASE_URL + "?token=abc_DEF-123");
        assertThat(html).doesNotContain("/auth/set-password");
    }

    @Test
    @DisplayName("RU recipient gets a Russian subject/body")
    void russianEmail() throws Exception {
        sender.sendPasswordReset(user("ru@example.com", "RU"), "tok");

        MimeMessage message = captureSentMessage();
        assertThat(message.getSubject()).isEqualTo("Сброс пароля — Foremen");
        String html = body(message);
        assertThat(html).contains("Здравствуйте, Jan Kowalski!");
        assertThat(html).contains("Установить новый пароль");
        assertThat(html).contains("действует 60 минут");
        assertThat(html).contains(BASE_URL + "?token=tok");
    }

    @Test
    @DisplayName("missing locale falls back to Polish")
    void nullLocaleFallsBackToPolish() throws Exception {
        sender.sendPasswordReset(user("x@example.com", null), "tok");

        assertThat(captureSentMessage().getSubject()).isEqualTo("Resetowanie hasła — Foremen");
    }

    @Test
    @DisplayName("reset link appends token as a query parameter and encodes it")
    void linkIsQueryParamEncoded() {
        assertThat(sender.buildResetLink("a b")).isEqualTo(BASE_URL + "?token=a%20b");
    }
}
