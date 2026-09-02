package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example test for request-locale resolution of invitation error message codes (task 12.3).
 *
 * <p>Unlike {@link ForemenControllerAdviceTest} (which mocks the {@link MessageResolver}),
 * this test wires a real {@code MessageResolver} backed by the real {@code messages}
 * resource bundles so the full advice-to-bundle resolution path is exercised end to end.
 *
 * <p>Asserts, following the existing {@code MessageResolverTest} patterns:
 * <ul>
 *   <li>An invite message code resolves to its Polish text when the request locale is PL (8.3).</li>
 *   <li>The same invite code resolves to its Russian text when the request locale is RU (8.3).</li>
 *   <li>A base-only code (present in {@code messages.properties} but absent from
 *       {@code messages_ru.properties}) falls back to the Polish value when RU is requested (8.5).</li>
 * </ul>
 */
class InviteErrorLocalizationTest {

    private ForemenControllerAdvice advice;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        // Fall back to the base bundle (PL) rather than the JVM default locale when a
        // code is missing for the resolved locale, so RU-missing codes resolve to PL text.
        messageSource.setFallbackToSystemLocale(false);

        MessageResolver messageResolver = new MessageResolver(messageSource);
        advice = new ForemenControllerAdvice(messageResolver);

        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.setRequestURI("/api/auth/set-password");
        mockRequest.setMethod("POST");
        request = mockRequest;
    }

    @Test
    @DisplayName("invite error code resolves to Polish text for a PL request locale")
    void inviteErrorResolvesToPolishForPlLocale() {
        ForemenApiException ex =
                new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invite.token.invalid");

        ResponseEntity<ErrorResponse> response =
                advice.handleForemenApiException(ex, request, Locale.of("pl"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("Zaproszenie jest nieprawidłowe.");
    }

    @Test
    @DisplayName("invite error code resolves to Russian text for a RU request locale")
    void inviteErrorResolvesToRussianForRuLocale() {
        ForemenApiException ex =
                new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invite.token.invalid");

        ResponseEntity<ErrorResponse> response =
                advice.handleForemenApiException(ex, request, Locale.of("ru"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("Приглашение недействительно.");
    }

    @Test
    @DisplayName("expired invite error code resolves per request locale (PL and RU)")
    void expiredInviteErrorResolvesPerLocale() {
        ForemenApiException ex =
                new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invite.token.expired");

        ResponseEntity<ErrorResponse> pl =
                advice.handleForemenApiException(ex, request, Locale.of("pl"));
        ResponseEntity<ErrorResponse> ru =
                advice.handleForemenApiException(ex, request, Locale.of("ru"));

        assertThat(pl.getBody()).isNotNull();
        assertThat(ru.getBody()).isNotNull();
        assertThat(pl.getBody().message())
                .isEqualTo("Zaproszenie wygasło. Poproś administratora o ponowne wysłanie zaproszenia.");
        assertThat(ru.getBody().message())
                .isEqualTo("Срок действия приглашения истёк. Попросите администратора отправить приглашение повторно.");
        // The two locales must produce distinct, localized messages.
        assertThat(ru.getBody().message()).isNotEqualTo(pl.getBody().message());
    }

    @Test
    @DisplayName("base-only code falls back to Polish text when RU locale is requested")
    void baseOnlyCodeFallsBackToPolishForRuLocale() {
        // error.role.system.name.immutable is defined only in the PL base bundle
        // (messages.properties) and has no Russian override in messages_ru.properties,
        // so a RU request must fall back to the Polish value (8.5).
        ForemenApiException ex =
                new ForemenApiException(HttpStatus.CONFLICT, "error.role.system.name.immutable");

        ResponseEntity<ErrorResponse> ru =
                advice.handleForemenApiException(ex, request, Locale.of("ru"));

        assertThat(ru.getBody()).isNotNull();
        assertThat(ru.getBody().message())
                .isEqualTo("Nie można zmienić nazwy roli systemowej.");
    }
}
