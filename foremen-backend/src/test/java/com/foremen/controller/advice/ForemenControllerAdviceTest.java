package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.ProjectNotReadyException;
import com.foremen.exception.dto.ErrorResponse;
import com.foremen.exception.dto.ProjectNotReadyError;
import com.foremen.service.readiness.AreaContribution;
import com.foremen.service.readiness.OutstandingItem;
import com.foremen.service.readiness.ReadinessState;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    @Test
    @DisplayName("ProjectNotReadyException returns 409 with a localized ProjectNotReadyError body (FOR-05-12 R4)")
    void projectNotReadyReturns409WithLocalizedBreakdown() {
        // Top-level message + each area name + each outstanding-item message resolve via MessageResolver.
        when(messageResolver.resolve(eq("error.project.not.ready"), isNull(), any(Locale.class)))
                .thenReturn("The project is not ready to be handed over to work.");
        when(messageResolver.resolve(eq("workspace.tab.estimate"), isNull(), any(Locale.class)))
                .thenReturn("Estimate");
        when(messageResolver.resolve(eq("workspace.tab.offer"), isNull(), any(Locale.class)))
                .thenReturn("Offer");
        when(messageResolver.resolve(eq("readiness.estimate.todo"), any(Object[].class), any(Locale.class)))
                .thenReturn("3 lines without price");
        // A blocked area with an empty params map resolves with null positional args.
        when(messageResolver.resolve(eq("readiness.offer.blocked"), isNull(), any(Locale.class)))
                .thenReturn("No offer prepared");

        AreaContribution estimate = new AreaContribution(
                "estimate", "workspace.tab.estimate", 40, ReadinessState.PARTIAL,
                List.of(new OutstandingItem("readiness.estimate.todo", Map.of("count", 3), "estimate")));
        AreaContribution offer = new AreaContribution(
                "offer", "workspace.tab.offer", 0, ReadinessState.BLOCKED,
                List.of(new OutstandingItem("readiness.offer.blocked", Map.of(), "offer")));
        ProjectNotReadyException ex = new ProjectNotReadyException(List.of(estimate, offer));

        ResponseEntity<ProjectNotReadyError> response =
                advice.handleProjectNotReady(ex, request, Locale.ENGLISH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProjectNotReadyError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo("The project is not ready to be handed over to work.");
        assertThat(body.areas()).hasSize(2);

        ProjectNotReadyError.NotReadyArea estimateArea = body.areas().get(0);
        assertThat(estimateArea.key()).isEqualTo("estimate");
        assertThat(estimateArea.name()).isEqualTo("Estimate");
        assertThat(estimateArea.state()).isEqualTo("PARTIAL");
        assertThat(estimateArea.percentage()).isEqualTo(40);
        assertThat(estimateArea.items()).containsExactly("3 lines without price");

        ProjectNotReadyError.NotReadyArea offerArea = body.areas().get(1);
        assertThat(offerArea.key()).isEqualTo("offer");
        assertThat(offerArea.name()).isEqualTo("Offer");
        assertThat(offerArea.state()).isEqualTo("BLOCKED");
        assertThat(offerArea.percentage()).isZero();
        assertThat(offerArea.items()).containsExactly("No offer prepared");
    }
}
