package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.FieldValidationException;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.ProjectNotReadyException;
import com.foremen.exception.WorkReportBatchItemException;
import com.foremen.exception.dto.ErrorResponse;
import com.foremen.exception.dto.ProjectNotReadyError;
import com.foremen.exception.dto.WorkReportBatchItemError;
import com.foremen.service.readiness.OutstandingItem;
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
import java.util.List;
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

    /**
     * FOR-06-01 Amendment A1 (Requirement 16.4; design A1.2) — a single item of a bulk
     * {@code POST /api/work-reports/batch} failed validation, aborting the all-or-nothing batch.
     * The response carries the failing item's position and identifiers plus the localized message
     * from the wrapped per-item {@link ForemenApiException}, under that exception's own HTTP status
     * (a validation failure resolves to {@code 400}), so the client can surface the error inline on
     * the offending cell/line.
     */
    @ExceptionHandler(WorkReportBatchItemException.class)
    public ResponseEntity<WorkReportBatchItemError> handleWorkReportBatchItem(
            WorkReportBatchItemException ex, HttpServletRequest request, Locale locale) {

        ForemenApiException cause = ex.getCause();
        String message = messageResolver.resolve(
                cause.getMessageCode(), cause.getMessageParams(), locale);
        HttpStatusCode status = cause.getStatus();

        logError(request, status.value(), cause.getMessageCode(), false, ex);

        WorkReportBatchItemError body = new WorkReportBatchItemError(
                ex.getIndex(), ex.getWorkItemId(), ex.getRoomId(), message);

        return ResponseEntity.status(status).body(body);
    }

    /**
     * FOR-05-12 (Requirement 4; design Decision D4) — the activation readiness gate rejected a
     * {@code pre-activation → ACTIVE} transition because the project is not fully ready. The
     * response is HTTP 409 with a {@link ProjectNotReadyError} body: the top-level
     * {@code error.project.not.ready} message plus the not-ready areas (state != {@code DONE}),
     * each with its localized name and outstanding-item messages. Every human-readable string is
     * resolved to the request locale here (Requirement 4.4), mirroring
     * {@link #handleWorkReportBatchItem}.
     */
    @ExceptionHandler(ProjectNotReadyException.class)
    public ResponseEntity<ProjectNotReadyError> handleProjectNotReady(
            ProjectNotReadyException ex, HttpServletRequest request, Locale locale) {

        String message = messageResolver.resolve("error.project.not.ready", null, locale);

        List<ProjectNotReadyError.NotReadyArea> areas = ex.getNotReadyAreas().stream()
                .map(area -> new ProjectNotReadyError.NotReadyArea(
                        area.key(),
                        messageResolver.resolve(area.nameKey(), null, locale),
                        area.state().name(),
                        area.percentage(),
                        area.items().stream()
                                .map(item -> resolveOutstandingItem(item, locale))
                                .toList()))
                .toList();

        logError(request, HttpStatus.CONFLICT.value(), "error.project.not.ready", false, ex);

        ProjectNotReadyError body = new ProjectNotReadyError(message, areas);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Resolves one readiness {@link OutstandingItem} to a localized sentence: its {@code
     * descriptionKey} against the request locale, with the item's named {@code params} values
     * supplied as positional message arguments.
     */
    private String resolveOutstandingItem(OutstandingItem item, Locale locale) {
        Object[] params = item.params() == null || item.params().isEmpty()
                ? null
                : item.params().values().toArray();
        return messageResolver.resolve(item.descriptionKey(), params, locale);
    }

    /**
     * FOR-05-09 (Requirement 13 criterion 4) — a business-rule validation failure that reports
     * multiple offending fields at once. Each map value is a message code, resolved to the request
     * language here (mirroring {@link #handleForemenApiException}), so the body lists every offending
     * field with a localized message under {@code fieldErrors} and a localized top-level
     * {@code error.validation} message, with HTTP 400.
     */
    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ErrorResponse> handleFieldValidation(
            FieldValidationException ex, HttpServletRequest request, Locale locale) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getFieldErrors().forEach((field, code) ->
                fieldErrors.put(field, messageResolver.resolve(code, null, locale)));

        String message = messageResolver.resolve("error.validation", null, locale);

        logError(request, 400, "error.validation", false, ex);

        ErrorResponse response = new ErrorResponse(
                Instant.now(), 400, "Bad Request", message,
                request.getRequestURI(), fieldErrors
        );

        return ResponseEntity.badRequest().body(response);
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
