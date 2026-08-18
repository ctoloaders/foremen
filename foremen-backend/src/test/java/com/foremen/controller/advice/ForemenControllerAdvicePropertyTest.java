package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for ForemenControllerAdvice.
 * Uses jqwik 1.9.2 for property testing.
 */
class ForemenControllerAdvicePropertyTest {

    /**
     * Property 1: Status Preservation Invariant
     *
     * For any ForemenApiException (or subclass) with any valid HttpStatusCode,
     * when handled by ForemenControllerAdvice, the returned ResponseEntity status code
     * SHALL equal the exception's status field value.
     *
     * Validates: Requirements 3.2, 7.1, 7.2, 8.1
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-01-03-exception, Property 1: Status Preservation Invariant")
    void statusPreservationInvariant(
            @ForAll("httpStatusCodes") int statusCode,
            @ForAll @StringLength(min = 1, max = 50) String messageCode) {

        // Arrange
        MessageResolver messageResolver = mock(MessageResolver.class);
        when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                .thenReturn("resolved-message");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");

        ForemenControllerAdvice advice = new ForemenControllerAdvice(messageResolver);

        HttpStatusCode httpStatusCode = HttpStatusCode.valueOf(statusCode);
        ForemenApiException exception = new ForemenApiException(httpStatusCode, messageCode);

        // Act
        ResponseEntity<ErrorResponse> response = advice.handleForemenApiException(
                exception, request, Locale.ENGLISH);

        // Assert
        assertThat(response.getStatusCode().value()).isEqualTo(statusCode);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(statusCode);
    }

    @Provide
    Arbitrary<Integer> httpStatusCodes() {
        // Generate valid HTTP status codes (standard ones that HttpStatus.valueOf can handle)
        return Arbitraries.of(
                200, 201, 202, 204,
                301, 302, 304,
                400, 401, 403, 404, 405, 406, 408, 409, 410, 415, 422, 429,
                500, 501, 502, 503, 504
        );
    }
}
