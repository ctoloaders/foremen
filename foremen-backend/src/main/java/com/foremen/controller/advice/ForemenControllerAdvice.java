package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ForemenControllerAdvice {

    private final MessageResolver messageResolver;

    @ExceptionHandler(ForemenApiException.class)
    public ResponseEntity<ErrorResponse> handleForemenApiException(
            ForemenApiException ex, HttpServletRequest request, Locale locale) {

        String message = messageResolver.resolve(ex.getMessageCode(), ex.getMessageParams(), locale);
        HttpStatusCode status = ex.getStatus();

        logError(request, status.value(), ex.getMessageCode(), false, ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(),
                status.value(),
                HttpStatus.valueOf(status.value()).getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request, Locale locale) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ex.getBindingResult().getGlobalErrors().forEach(error ->
                fieldErrors.put(error.getObjectName(), error.getDefaultMessage()));

        String message = messageResolver.resolve("error.validation", null, locale);

        logError(request, 400, "error.validation", false, ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(), 400, "Bad Request", message,
                request.getRequestURI(), fieldErrors
        );

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request, Locale locale) {

        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage(),
                        (v1, v2) -> v1,
                        LinkedHashMap::new
                ));

        String message = messageResolver.resolve("error.validation", null, locale);

        logError(request, 400, "error.validation", false, ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(), 400, "Bad Request", message,
                request.getRequestURI(), fieldErrors
        );

        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request, Locale locale) {

        String message = messageResolver.resolve("error.data.integrity", null, locale);

        logError(request, 409, "error.data.integrity", false, ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(), 409, "Conflict", message, request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request, Locale locale) {

        String message = messageResolver.resolve("error.access.denied", null, locale);

        logError(request, 403, "error.access.denied", false, ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(), 403, "Forbidden", message, request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            RuntimeException ex, HttpServletRequest request, Locale locale) {

        String message = messageResolver.resolve("error.internal", null, locale);

        logError(request, 500, "error.internal", true, ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(), 500, "Internal Server Error", message,
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // --- Private helpers ---

    private void logError(HttpServletRequest request, int status, String messageCode,
                          boolean includeStackTrace, Exception ex) {
        String username = extractUsername();
        String method = request.getMethod();
        String uri = request.getRequestURI();

        if (includeStackTrace) {
            log.error("Exception handled: code={}, method={}, uri={}, user={}, status={}",
                    messageCode, method, uri, username, status, ex);
        } else {
            log.error("Exception handled: code={}, method={}, uri={}, user={}, status={}",
                    messageCode, method, uri, username, status);
        }
    }

    private String extractUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "anonymous";
        }
        return authentication.getName();
    }
}
