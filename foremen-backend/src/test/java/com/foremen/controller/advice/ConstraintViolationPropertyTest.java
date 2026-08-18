package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import net.jqwik.api.*;
import org.springframework.http.ResponseEntity;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property 8: ConstraintViolation Mapping Completeness
 *
 * For any set of (propertyPath, message) pairs with unique property paths,
 * the handler SHALL produce a fieldErrors map that matches the input exactly.
 *
 * Validates: Requirements 6.4
 */
class ConstraintViolationPropertyTest {

    @Property(tries = 100)
    @Tag("Feature: FOR-01-03-exception, Property 8: ConstraintViolation Mapping Completeness")
    void constraintViolationFieldErrorsMatchInput(
            @ForAll("violationEntries") List<Map.Entry<String, String>> entries) {

        // Arrange
        MessageResolver messageResolver = mock(MessageResolver.class);
        when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                .thenReturn("Validation error");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/resource");
        when(request.getMethod()).thenReturn("POST");

        ForemenControllerAdvice advice = new ForemenControllerAdvice(messageResolver);

        // Build mocked ConstraintViolation set
        Set<ConstraintViolation<?>> violations = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : entries) {
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);

            Path path = mock(Path.class);
            when(path.toString()).thenReturn(entry.getKey());
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn(entry.getValue());

            violations.add(violation);
        }

        ConstraintViolationException exception = new ConstraintViolationException(violations);

        // Act
        ResponseEntity<ErrorResponse> response = advice.handleConstraintViolation(
                exception, request, Locale.ENGLISH);

        // Assert
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldErrors()).isNotNull();
        assertThat(response.getBody().fieldErrors()).hasSize(entries.size());

        // Verify each property path maps to the correct message
        for (Map.Entry<String, String> entry : entries) {
            assertThat(response.getBody().fieldErrors())
                    .containsEntry(entry.getKey(), entry.getValue());
        }
    }

    @Provide
    Arbitrary<List<Map.Entry<String, String>>> violationEntries() {
        Arbitrary<String> propertyPaths = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(30);

        Arbitrary<String> messages = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);

        return Combinators.combine(propertyPaths, messages)
                .<Map.Entry<String, String>>as(AbstractMap.SimpleEntry::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(15)
                .uniqueElements(Map.Entry::getKey)
                .map(list -> list);
    }
}
