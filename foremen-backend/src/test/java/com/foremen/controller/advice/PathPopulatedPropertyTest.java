package com.foremen.controller.advice;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test: Path Populated From Request URI.
 * Generates random valid URI paths, mocks HttpServletRequest, invokes handler,
 * and asserts response.path equals the generated URI.
 *
 * Validates: Requirements 3.8, 8.3
 */
@Tag("Feature: FOR-01-03-exception, Property 4: Path Populated From Request URI")
class PathPopulatedPropertyTest {

    private final MessageResolver messageResolver;
    private final ForemenControllerAdvice advice;

    PathPopulatedPropertyTest() {
        messageResolver = mock(MessageResolver.class);
        when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                .thenReturn("error occurred");
        advice = new ForemenControllerAdvice(messageResolver);
    }

    /**
     * Property 4: For any valid URI path, the handler populates response.path
     * with that exact URI.
     *
     * Validates: Requirements 3.8, 8.3
     */
    @Property(tries = 100)
    void pathInResponseMatchesRequestUri(@ForAll("validUriPaths") String uriPath) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uriPath);
        when(request.getMethod()).thenReturn("GET");

        RuntimeException ex = new RuntimeException("test error");

        var response = advice.handleGenericException(ex, request, Locale.ENGLISH);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path()).isEqualTo(uriPath);
    }

    @Provide
    Arbitrary<String> validUriPaths() {
        // Generate URI paths like /api/projects/123 or /users/abc/details
        Arbitrary<String> segment = Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(20);

        return segment.list()
                .ofMinSize(1)
                .ofMaxSize(5)
                .map(segments -> "/" + String.join("/", segments));
    }
}
