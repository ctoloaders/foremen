package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property 9: Subclass Polymorphic Handling
 *
 * For any subclass of ForemenApiException with any status code and message code,
 * ForemenControllerAdvice SHALL catch it via the handleForemenApiException handler
 * and produce an ErrorResponse with matching status and resolved message —
 * without requiring additional handler methods or configuration.
 *
 * Validates: Requirements 7.1, 7.2
 */
class SubclassPolymorphicPropertyTest {

    private static final String RESOLVED_MESSAGE = "resolved-polymorphic-message";

    @Property(tries = 100)
    @Tag("Feature: FOR-01-03-exception, Property 9: Subclass Polymorphic Handling")
    void subclassIsHandledByBaseExceptionHandler(
            @ForAll("httpStatusCodes") int statusCode,
            @ForAll("messageCodes") String messageCode) {

        // Arrange
        MessageResolver messageResolver = mock(MessageResolver.class);
        when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                .thenReturn(RESOLVED_MESSAGE);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/subclass/test");
        when(request.getMethod()).thenReturn("POST");

        ForemenControllerAdvice advice = new ForemenControllerAdvice(messageResolver);

        HttpStatusCode httpStatusCode = HttpStatusCode.valueOf(statusCode);

        // Create an anonymous subclass of ForemenApiException
        ForemenApiException subclassException = new ForemenApiException(httpStatusCode, messageCode) {};

        // Act
        ResponseEntity<ErrorResponse> response = advice.handleForemenApiException(
                subclassException, request, Locale.ENGLISH);

        // Assert — response status matches the exception's status
        assertThat(response.getStatusCode().value()).isEqualTo(statusCode);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(statusCode);

        // Assert — messageResolver.resolve() was called with the exception's messageCode
        verify(messageResolver).resolve(eq(messageCode), any(), any(Locale.class));
    }

    @Provide
    Arbitrary<Integer> httpStatusCodes() {
        return Arbitraries.of(
                400, 401, 403, 404, 409, 422,
                500, 501, 502, 503
        );
    }

    @Provide
    Arbitrary<String> messageCodes() {
        // Generate message codes like "error.project.not.found", "error.task.invalid"
        Arbitrary<String> segments = Arbitraries.strings()
                .alpha()
                .ofMinLength(2)
                .ofMaxLength(10)
                .map(String::toLowerCase);

        return segments.list().ofMinSize(2).ofMaxSize(5)
                .map(parts -> String.join(".", parts));
    }
}
