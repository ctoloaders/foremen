package com.foremen.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ForemenValidationException.
 * Validates: Requirements 7.3 — preset status 400, extends ForemenApiException.
 */
class ForemenValidationExceptionTest {

    @Test
    void extendsForemenApiException() {
        var exception = new ForemenValidationException("test.code");

        assertThat(exception).isInstanceOf(ForemenApiException.class);
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void statusIsAlwaysBadRequest() {
        var exception = new ForemenValidationException("some.code", "param1", "param2");

        assertThat(exception.getStatus().value()).isEqualTo(400);
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void messageCodeIsSet() {
        var exception = new ForemenValidationException("error.field.invalid");

        assertThat(exception.getMessageCode()).isEqualTo("error.field.invalid");
    }

    @Test
    void messageParamsAreSet() {
        var exception = new ForemenValidationException("error.field.range", "min", 10);

        assertThat(exception.getMessageParams()).containsExactly("min", 10);
    }

    @Test
    void defaultMessageParamsIsEmptyArray() {
        var exception = new ForemenValidationException("error.code.only");

        assertThat(exception.getMessageParams()).isEmpty();
    }
}
