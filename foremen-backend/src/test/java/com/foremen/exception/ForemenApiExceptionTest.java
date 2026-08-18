package com.foremen.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;

class ForemenApiExceptionTest {

    @Test
    @DisplayName("ForemenApiException extends RuntimeException")
    void extendsRuntimeException() {
        var exception = new ForemenApiException(HttpStatus.BAD_REQUEST, "error.test");

        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Full constructor sets all fields correctly")
    void fullConstructorSetsAllFields() {
        HttpStatusCode status = HttpStatus.NOT_FOUND;
        String messageCode = "error.not.found";
        Object[] params = {"param1", 42};

        var exception = new ForemenApiException(status, messageCode, params);

        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getMessageCode()).isEqualTo(messageCode);
        assertThat(exception.getMessageParams()).containsExactly("param1", 42);
    }

    @Test
    @DisplayName("Short constructor sets messageParams to empty array")
    void shortConstructorSetsEmptyMessageParams() {
        var exception = new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal");

        assertThat(exception.getMessageParams()).isNotNull();
        assertThat(exception.getMessageParams()).isEmpty();
    }

    @Test
    @DisplayName("Null messageParams is normalized to empty array")
    void nullMessageParamsNormalizedToEmptyArray() {
        var exception = new ForemenApiException(HttpStatus.BAD_REQUEST, "error.test", (Object[]) null);

        assertThat(exception.getMessageParams()).isNotNull();
        assertThat(exception.getMessageParams()).hasSize(0);
    }

    @Test
    @DisplayName("getMessage() returns the messageCode")
    void superMessageIsMessageCode() {
        String messageCode = "error.custom.code";
        var exception = new ForemenApiException(HttpStatus.CONFLICT, messageCode);

        assertThat(exception.getMessage()).isEqualTo(messageCode);
    }

    @Test
    @DisplayName("Lombok @Getter annotations generate correct accessors")
    void getterAnnotationsWork() {
        HttpStatusCode status = HttpStatus.FORBIDDEN;
        String messageCode = "error.access.denied";
        Object[] params = {"resource", "admin"};

        var exception = new ForemenApiException(status, messageCode, params);

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exception.getMessageCode()).isEqualTo("error.access.denied");
        assertThat(exception.getMessageParams()).containsExactly("resource", "admin");
    }
}
