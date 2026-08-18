package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test: Handler Never Throws.
 *
 * Validates: Requirements 8.7
 *
 * Generate random RuntimeException subclasses with random messages (including null message)
 * → invoke handler → assert no exception thrown + valid ResponseEntity returned.
 * The handler must handle ANY exception without throwing a new exception.
 */
@Tag("Feature: FOR-01-03-exception, Property 6: Handler Never Throws")
class HandlerNeverThrowsPropertyTest {

    private static final String RESOLVED_MESSAGE = "Internal server error";

    private final MessageResolver messageResolver = mock(MessageResolver.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ForemenControllerAdvice advice;

    HandlerNeverThrowsPropertyTest() {
        when(messageResolver.resolve(eq("error.internal"), isNull(), any(Locale.class)))
                .thenReturn(RESOLVED_MESSAGE);
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        advice = new ForemenControllerAdvice(messageResolver);
    }

    /**
     * Validates: Requirements 8.7
     *
     * For any RuntimeException with any message (null, empty, long, special chars, SQL-like,
     * stack-trace-like), the handler never throws and returns a valid 500 ResponseEntity.
     */
    @Property(tries = 100)
    void handlerNeverThrowsForAnyRuntimeException(
            @ForAll("runtimeExceptions") RuntimeException exception) {

        assertThatCode(() -> advice.handleGenericException(exception, request, Locale.getDefault()))
                .doesNotThrowAnyException();

        ResponseEntity<ErrorResponse> response = advice.handleGenericException(
                exception, request, Locale.getDefault());

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isNotNull().isNotEmpty();
        assertThat(response.getBody().path()).isEqualTo("/api/test");
    }

    @Provide
    Arbitrary<RuntimeException> runtimeExceptions() {
        Arbitrary<String> messages = Arbitraries.oneOf(
                // null message
                Arbitraries.just(null),
                // empty string
                Arbitraries.just(""),
                // very long string
                Arbitraries.strings().ofMinLength(500).ofMaxLength(2000),
                // strings with special characters
                Arbitraries.of(
                        "Error with <html>tags</html>",
                        "Exception: \t\n\r special chars",
                        "Unicode: \uFFFF\u200B\u00E9\u00F1",
                        "Control chars: \u0001\u0002\u001F"
                ),
                // SQL-like strings
                Arbitraries.of(
                        "SELECT * FROM users WHERE id = 1; DROP TABLE users;--",
                        "'; DELETE FROM accounts; --",
                        "INSERT INTO logs VALUES('hack')"
                ),
                // stack-trace-like strings
                Arbitraries.of(
                        "at com.foremen.service.UserService.findById(UserService.java:42)",
                        "java.lang.NullPointerException\n\tat com.example.Main.main(Main.java:10)",
                        "Caused by: org.hibernate.exception.ConstraintViolationException"
                ),
                // normal random strings
                Arbitraries.strings().ofMinLength(1).ofMaxLength(100)
        );

        return messages.map(msg -> {
            if (msg == null) {
                return new RuntimeException((String) null);
            }
            return new RuntimeException(msg);
        });
    }
}
