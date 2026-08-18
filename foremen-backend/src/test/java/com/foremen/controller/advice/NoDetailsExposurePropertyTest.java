package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test: No Internal Details Exposure.
 *
 * Validates: Requirements 3.7
 *
 * For all random RuntimeException messages (including SQL, stack-trace-like strings),
 * the generic exception handler must NOT expose the original exception message in the response.
 */
@Tag("Feature: FOR-01-03-exception, Property 3: No Internal Details Exposure")
class NoDetailsExposurePropertyTest {

    private static final String RESOLVED_MESSAGE = "Внутренняя ошибка";

    private final MessageResolver messageResolver = mock(MessageResolver.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ForemenControllerAdvice advice = new ForemenControllerAdvice(messageResolver);

    /**
     * Validates: Requirements 3.7
     *
     * Generate random RuntimeException messages → invoke generic handler →
     * assert response message does NOT contain original exception message
     * and equals the resolved i18n message.
     */
    @Property(tries = 100)
    void responseNeverExposesInternalExceptionMessage(
            @ForAll @StringLength(min = 1, max = 200) String exceptionMessage) {

        // Skip if generated message happens to equal or be a substring of the resolved message
        // (e.g., a single space " " is technically contained in any string with spaces)
        Assume.that(!RESOLVED_MESSAGE.contains(exceptionMessage));

        when(messageResolver.resolve(eq("error.internal"), isNull(), any(Locale.class)))
                .thenReturn(RESOLVED_MESSAGE);
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");

        RuntimeException runtimeException = new RuntimeException(exceptionMessage);

        ResponseEntity<ErrorResponse> response = advice.handleGenericException(
                runtimeException, request, Locale.getDefault());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain(exceptionMessage);
        assertThat(response.getBody().message()).isEqualTo(RESOLVED_MESSAGE);
    }
}
