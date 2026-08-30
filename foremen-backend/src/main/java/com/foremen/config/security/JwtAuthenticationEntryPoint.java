package com.foremen.config.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foremen.config.i18n.MessageResolver;
import com.foremen.exception.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Produces the pre-controller HTTP 401 response when an unauthenticated request reaches a
 * protected endpoint (Requirements 6.4, 9.3).
 *
 * <p>The body is shaped like {@link ErrorResponse} so 401 responses stay consistent with the
 * {@code ForemenControllerAdvice} pipeline. The message is resolved from
 * {@code error.auth.unauthorized} in the request locale (via {@link LocaleContextHolder}, the same
 * source the rest of the application uses) and written as JSON with a Jackson {@link ObjectMapper}.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String UNAUTHORIZED_MESSAGE_CODE = "error.auth.unauthorized";

    private final MessageResolver messageResolver;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(MessageResolver messageResolver) {
        this.messageResolver = messageResolver;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageResolver.resolve(UNAUTHORIZED_MESSAGE_CODE, null, locale);

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
