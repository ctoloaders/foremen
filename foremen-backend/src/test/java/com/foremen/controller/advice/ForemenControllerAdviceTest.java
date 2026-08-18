package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForemenControllerAdviceTest {

    @Mock
    private MessageResolver messageResolver;

    @Mock
    private HttpServletRequest request;

    private ForemenControllerAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new ForemenControllerAdvice(messageResolver);
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
    }

    @Test
    @DisplayName("DataIntegrityViolationException returns 409 Conflict with resolved message")
    void dataIntegrityViolationReturns409() {
        when(messageResolver.resolve(eq("error.data.integrity"), isNull(), any(Locale.class)))
                .thenReturn("Data integrity violation");

        DataIntegrityViolationException ex = new DataIntegrityViolationException("duplicate key");

        ResponseEntity<ErrorResponse> response = advice.handleDataIntegrity(ex, request, Locale.ENGLISH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("Conflict");
        assertThat(response.getBody().message()).isEqualTo("Data integrity violation");
    }

    @Test
    @DisplayName("AccessDeniedException returns 403 Forbidden with resolved message")
    void accessDeniedReturns403() {
        when(messageResolver.resolve(eq("error.access.denied"), isNull(), any(Locale.class)))
                .thenReturn("Access denied");

        AccessDeniedException ex = new AccessDeniedException("not allowed");

        ResponseEntity<ErrorResponse> response = advice.handleAccessDenied(ex, request, Locale.ENGLISH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().error()).isEqualTo("Forbidden");
        assertThat(response.getBody().message()).isEqualTo("Access denied");
    }

    @Test
    @DisplayName("Generic RuntimeException returns 500 Internal Server Error with resolved message")
    void genericRuntimeExceptionReturns500() {
        when(messageResolver.resolve(eq("error.internal"), isNull(), any(Locale.class)))
                .thenReturn("Internal server error");

        RuntimeException ex = new RuntimeException("something went wrong internally");

        ResponseEntity<ErrorResponse> response = advice.handleGenericException(ex, request, Locale.ENGLISH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
    }

    @Test
    @DisplayName("Generic exception response does not expose original exception message")
    void genericExceptionResponseDoesNotExposeOriginalMessage() {
        String sensitiveMessage = "SQL Error: connection to db at 192.168.1.1 failed";
        when(messageResolver.resolve(eq("error.internal"), isNull(), any(Locale.class)))
                .thenReturn("An unexpected error occurred");

        RuntimeException ex = new RuntimeException(sensitiveMessage);

        ResponseEntity<ErrorResponse> response = advice.handleGenericException(ex, request, Locale.ENGLISH);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain(sensitiveMessage);
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }

    @Test
    @DisplayName("ForemenApiException is handled with correct status from exception")
    void foremenApiExceptionHandledWithCorrectStatus() {
        when(messageResolver.resolve(eq("error.not.found"), any(), any(Locale.class)))
                .thenReturn("Resource not found");

        ForemenApiException ex = new ForemenApiException(HttpStatus.NOT_FOUND, "error.not.found");

        ResponseEntity<ErrorResponse> response = advice.handleForemenApiException(ex, request, Locale.ENGLISH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
    }

    @Test
    @DisplayName("Timestamp is populated in error response")
    void timestampIsPopulated() {
        when(messageResolver.resolve(eq("error.internal"), isNull(), any(Locale.class)))
                .thenReturn("error");

        RuntimeException ex = new RuntimeException("test");

        ResponseEntity<ErrorResponse> response = advice.handleGenericException(ex, request, Locale.ENGLISH);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    @DisplayName("Path is populated from request URI")
    void pathIsPopulatedFromRequest() {
        when(request.getRequestURI()).thenReturn("/api/v1/projects/123");
        when(messageResolver.resolve(eq("error.internal"), isNull(), any(Locale.class)))
                .thenReturn("error");

        RuntimeException ex = new RuntimeException("test");

        ResponseEntity<ErrorResponse> response = advice.handleGenericException(ex, request, Locale.ENGLISH);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/projects/123");
    }
}
