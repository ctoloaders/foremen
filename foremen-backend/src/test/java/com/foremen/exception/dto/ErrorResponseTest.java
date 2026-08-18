package com.foremen.exception.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ErrorResponse record.
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7
 */
class ErrorResponseTest {

    private static final Instant TIMESTAMP = Instant.parse("2025-01-15T10:30:00Z");
    private static final int STATUS = 400;
    private static final String ERROR = "Bad Request";
    private static final String MESSAGE = "Validation failed";
    private static final String PATH = "/api/projects";
    private static final Map<String, String> FIELD_ERRORS = Map.of("name", "must not be blank");

    @Test
    void isARecord() {
        assertThat(ErrorResponse.class.isRecord()).isTrue();
    }

    @Test
    void canonicalConstructorSetsAllFields() {
        var response = new ErrorResponse(TIMESTAMP, STATUS, ERROR, MESSAGE, PATH, FIELD_ERRORS);

        assertThat(response.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(response.status()).isEqualTo(STATUS);
        assertThat(response.error()).isEqualTo(ERROR);
        assertThat(response.message()).isEqualTo(MESSAGE);
        assertThat(response.path()).isEqualTo(PATH);
        assertThat(response.fieldErrors()).isEqualTo(FIELD_ERRORS);
    }

    @Test
    void convenienceConstructorSetsFieldErrorsToNull() {
        var response = new ErrorResponse(TIMESTAMP, STATUS, ERROR, MESSAGE, PATH);

        assertThat(response.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(response.status()).isEqualTo(STATUS);
        assertThat(response.error()).isEqualTo(ERROR);
        assertThat(response.message()).isEqualTo(MESSAGE);
        assertThat(response.path()).isEqualTo(PATH);
        assertThat(response.fieldErrors()).isNull();
    }

    @Test
    void recordComponents() {
        var components = ErrorResponse.class.getRecordComponents();

        assertThat(components).hasSize(6);

        var componentNames = java.util.Arrays.stream(components)
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactly(
                "timestamp", "status", "error", "message", "path", "fieldErrors"
        );
    }

    @Test
    void equalsAndHashCode() {
        var response1 = new ErrorResponse(TIMESTAMP, STATUS, ERROR, MESSAGE, PATH, FIELD_ERRORS);
        var response2 = new ErrorResponse(TIMESTAMP, STATUS, ERROR, MESSAGE, PATH, FIELD_ERRORS);

        assertThat(response1).isEqualTo(response2);
        assertThat(response1.hashCode()).isEqualTo(response2.hashCode());
    }

    @Test
    void nullFieldErrors() {
        var response = new ErrorResponse(TIMESTAMP, STATUS, ERROR, MESSAGE, PATH);

        assertThat(response.fieldErrors()).isNull();
    }
}
