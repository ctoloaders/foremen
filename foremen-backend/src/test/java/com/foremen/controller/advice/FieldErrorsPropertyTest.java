package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property 2: Field Errors Count Preservation
 *
 * For any MethodArgumentNotValidException containing N field errors (with unique field names),
 * the ErrorResponse.fieldErrors map SHALL contain exactly N entries.
 *
 * Validates: Requirements 3.3, 6.1, 6.2, 8.5
 */
class FieldErrorsPropertyTest {

    @Property(tries = 100)
    @Tag("Feature: FOR-01-03-exception, Property 2: Field Errors Count Preservation")
    void fieldErrorsCountIsPreserved(
            @ForAll("fieldErrorCount") int count) {

        // Arrange
        MessageResolver messageResolver = mock(MessageResolver.class);
        when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                .thenReturn("Validation error");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("POST");

        ForemenControllerAdvice advice = new ForemenControllerAdvice(messageResolver);

        // Generate unique field errors
        List<FieldError> fieldErrors = IntStream.range(0, count)
                .mapToObj(i -> new FieldError("testObject", "field_" + i, "error message " + i))
                .toList();

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(fieldErrors);
        when(bindingResult.getGlobalErrors()).thenReturn(List.of());

        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(null, bindingResult);

        // Act
        ResponseEntity<ErrorResponse> response = advice.handleValidationException(
                exception, request, Locale.ENGLISH);

        // Assert
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldErrors()).isNotNull();
        assertThat(response.getBody().fieldErrors()).hasSize(count);
    }

    @Provide
    Arbitrary<Integer> fieldErrorCount() {
        return Arbitraries.integers().between(1, 20);
    }
}
