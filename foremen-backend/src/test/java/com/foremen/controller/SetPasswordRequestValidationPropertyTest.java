package com.foremen.controller;

// Feature: FOR-03-02-user-invitation, Property 10: Set-password rejects invalid request fields before lookup

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.controller.dto.auth.SetPasswordRequest;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Property test for the set-password endpoint's request-validation contract
 * (Feature: FOR-03-02-user-invitation).
 *
 * <p><b>Property 10: Set-password rejects invalid request fields before lookup</b> &mdash;
 * <i>for all</i> set-password requests carrying an invalid field (a blank token, or a password
 * whose length is outside 8..72), the {@link AuthController} rejects the request with HTTP 400
 * through bean validation, and the {@link AuthService} is never consulted &mdash; i.e. no token
 * lookup happens (Requirement 5.3).
 *
 * <p>Setup: a standalone MockMvc wiring the real {@link AuthController} and
 * {@link ForemenControllerAdvice} (so {@code @Valid} failures surface as HTTP 400 exactly as in
 * production) with mocked {@link AuthService} and {@link InviteService}. A fresh mock/MockMvc is
 * built per example so {@code verifyNoInteractions(authService)} proves that no lookup was reached
 * for that specific input. This is a pure jqwik container (no JUnit {@code @Test} methods) so the
 * jqwik engine owns the class and every property is executed.
 *
 * <b>Validates: Requirements 5.3</b>
 */
class SetPasswordRequestValidationPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Serializes a {@link SetPasswordRequest} to a well-formed JSON body. Using a real serializer
     * (rather than string concatenation) guarantees whitespace-only tokens are correctly escaped,
     * so the request reaches bean validation as a blank-but-valid JSON string instead of failing as
     * malformed JSON.
     */
    private static String body(String token, String password) throws Exception {
        return MAPPER.writeValueAsString(new SetPasswordRequest(token, password));
    }

    private static Harness newHarness() {
        return new Harness();
    }

    /** A fresh controller + advice + mocked services behind a standalone MockMvc, per example. */
    private static final class Harness {
        final AuthService authService = mock(AuthService.class);
        final InviteService inviteService = mock(InviteService.class);
        final MockMvc mockMvc;

        Harness() {
            MessageResolver messageResolver = mock(MessageResolver.class);
            when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                    .thenReturn("validation error");
            mockMvc = MockMvcBuilders
                    .standaloneSetup(new AuthController(authService, inviteService))
                    .setControllerAdvice(new ForemenControllerAdvice(messageResolver))
                    .build();
        }
    }

    /** Lengths below the 8-char minimum (1..7) and above the 72-char maximum (73..120). */
    @Provide
    Arbitrary<Integer> outOfRangePasswordLengths() {
        return Arbitraries.oneOf(
                Arbitraries.integers().between(1, 7),
                Arbitraries.integers().between(73, 120));
    }

    /** Whitespace-only tokens (space, tab) of length 1..10, plus the empty string. */
    @Provide
    Arbitrary<String> blankTokens() {
        Arbitrary<String> whitespace = Arbitraries.of(' ', '\t')
                .list().ofMinSize(1).ofMaxSize(10)
                .map(chars -> {
                    StringBuilder sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
        return Arbitraries.oneOf(whitespace, Arbitraries.just(""));
    }

    // Feature: FOR-03-02-user-invitation, Property 10: Set-password rejects invalid request fields before lookup
    // For every out-of-range password length, the request is 400 and no lookup is reached.
    // Validates: Requirements 5.3
    @Property(tries = 100)
    void outOfRangePasswordRejectedBeforeLookup(
            @ForAll("outOfRangePasswordLengths") int length) throws Exception {

        Harness h = newHarness();

        h.mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(body("valid-token", "a".repeat(length))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(h.authService);
    }

    // Feature: FOR-03-02-user-invitation, Property 10: Set-password rejects invalid request fields before lookup
    // For every blank token (empty or whitespace-only), the request is 400 and no lookup is reached.
    // Validates: Requirements 5.3
    @Property(tries = 100)
    void blankTokenRejectedBeforeLookup(@ForAll("blankTokens") String blankToken) throws Exception {

        Harness h = newHarness();

        h.mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(body(blankToken, "password123")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(h.authService);
    }
}
