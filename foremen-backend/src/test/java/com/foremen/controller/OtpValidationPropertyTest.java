package com.foremen.controller;

// Feature: FOR-03-05-otp-client-auth, Property 6: Blank request fields are rejected before any side effect

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import com.foremen.service.OtpService;
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
 * Property test for the OTP endpoints' request-validation contract
 * (Feature: FOR-03-05-otp-client-auth).
 *
 * <p><b>Property 6: Blank request fields are rejected before any side effect</b> &mdash;
 * <i>for all</i> {@code /api/auth/otp/request} bodies with a blank or missing email, and
 * <i>for all</i> {@code /api/auth/otp/verify} bodies with a blank or missing email or code, the
 * {@link AuthController} rejects the request with HTTP 400 through bean validation, and the
 * downstream collaborators are never consulted &mdash; i.e. no rate-limit count, no user lookup, no
 * code lookup, no persistence, and no email dispatch happens. For {@code otp/request} that means
 * {@link OtpService} is never invoked; for {@code otp/verify} that means {@link AuthService} is
 * never invoked (Requirements 4.2, 6.2).
 *
 * <p>Setup: a standalone MockMvc wiring the real {@link AuthController} and
 * {@link ForemenControllerAdvice} (so {@code @Valid} failures surface as HTTP 400 exactly as in
 * production) with mocked {@link AuthService}, {@link InviteService}, and {@link OtpService}. A
 * fresh mock/MockMvc is built per example so {@code verifyNoInteractions(...)} proves that no side
 * effect was reached for that specific input. This is a pure jqwik container (no JUnit
 * {@code @Test} methods) so the jqwik engine owns the class and every property is executed.
 *
 * <b>Validates: Requirements 4.2, 6.2</b>
 */
class OtpValidationPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Serializes an OTP-request body. When {@code email} is {@code null} the field is omitted from
     * the JSON entirely (missing email); otherwise it is written as a JSON string (so
     * whitespace-only values are correctly escaped and reach bean validation as a blank-but-valid
     * JSON string rather than failing as malformed JSON).
     */
    private static String requestBody(String email) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        if (email != null) {
            node.put("email", email);
        }
        return MAPPER.writeValueAsString(node);
    }

    /** Serializes an OTP-verify body, omitting any field whose value is {@code null}. */
    private static String verifyBody(String email, String code) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        if (email != null) {
            node.put("email", email);
        }
        if (code != null) {
            node.put("code", code);
        }
        return MAPPER.writeValueAsString(node);
    }

    private static Harness newHarness() {
        return new Harness();
    }

    /** A fresh controller + advice + mocked services behind a standalone MockMvc, per example. */
    private static final class Harness {
        final AuthService authService = mock(AuthService.class);
        final InviteService inviteService = mock(InviteService.class);
        final OtpService otpService = mock(OtpService.class);
        final MockMvc mockMvc;

        Harness() {
            MessageResolver messageResolver = mock(MessageResolver.class);
            when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                    .thenReturn("validation error");
            mockMvc = MockMvcBuilders
                    .standaloneSetup(new AuthController(authService, inviteService, otpService))
                    .setControllerAdvice(new ForemenControllerAdvice(messageResolver))
                    .build();
        }
    }

    /**
     * Blank strings: the empty string plus whitespace-only strings (space, tab) of length 1..10.
     * These are all rejected by {@code @NotBlank}.
     */
    @Provide
    Arbitrary<String> blankStrings() {
        Arbitrary<String> whitespace = Arbitraries.of(' ', '\t')
                .list().ofMinSize(1).ofMaxSize(10)
                .map(chars -> {
                    StringBuilder sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
        return Arbitraries.oneOf(whitespace, Arbitraries.just(""));
    }

    /**
     * A blank or missing email: either a blank string (empty/whitespace-only) or {@code null},
     * where {@code null} models the field being omitted from the request body entirely.
     */
    @Provide
    Arbitrary<String> blankOrMissingEmails() {
        return Arbitraries.oneOf(blankStrings(), Arbitraries.just((String) null));
    }

    /** A non-blank, well-formed email used where the counterpart field must remain valid. */
    @Provide
    Arbitrary<String> validEmails() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10)
                .map(local -> local + "@example.com");
    }

    /** A non-blank code used where the code field must remain valid. */
    @Provide
    Arbitrary<String> validCodes() {
        return Arbitraries.strings().numeric().ofLength(6);
    }

    // Feature: FOR-03-05-otp-client-auth, Property 6: Blank request fields are rejected before any side effect
    // For every blank/missing email on otp/request, the request is 400 and OtpService is never invoked.
    // Validates: Requirements 4.2
    @Property(tries = 100)
    void otpRequestBlankEmailRejectedBeforeSideEffect(
            @ForAll("blankOrMissingEmails") String email) throws Exception {

        Harness h = newHarness();

        h.mockMvc.perform(post("/api/auth/otp/request")
                        .contentType("application/json")
                        .content(requestBody(email)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(h.otpService);
    }

    // Feature: FOR-03-05-otp-client-auth, Property 6: Blank request fields are rejected before any side effect
    // For every blank/missing email on otp/verify (code kept valid), the request is 400 and AuthService is never invoked.
    // Validates: Requirements 6.2
    @Property(tries = 100)
    void otpVerifyBlankEmailRejectedBeforeSideEffect(
            @ForAll("blankOrMissingEmails") String email,
            @ForAll("validCodes") String code) throws Exception {

        Harness h = newHarness();

        h.mockMvc.perform(post("/api/auth/otp/verify")
                        .contentType("application/json")
                        .content(verifyBody(email, code)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(h.authService);
    }

    // Feature: FOR-03-05-otp-client-auth, Property 6: Blank request fields are rejected before any side effect
    // For every blank/missing code on otp/verify (email kept valid), the request is 400 and AuthService is never invoked.
    // Validates: Requirements 6.2
    @Property(tries = 100)
    void otpVerifyBlankCodeRejectedBeforeSideEffect(
            @ForAll("validEmails") String email,
            @ForAll("blankOrMissingEmails") String code) throws Exception {

        Harness h = newHarness();

        h.mockMvc.perform(post("/api/auth/otp/verify")
                        .contentType("application/json")
                        .content(verifyBody(email, code)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(h.authService);
    }

    // Feature: FOR-03-05-otp-client-auth, Property 6: Blank request fields are rejected before any side effect
    // For every combination where both email and code are blank/missing on otp/verify, the request is
    // 400 and AuthService is never invoked.
    // Validates: Requirements 6.2
    @Property(tries = 100)
    void otpVerifyBlankEmailAndCodeRejectedBeforeSideEffect(
            @ForAll("blankOrMissingEmails") String email,
            @ForAll("blankOrMissingEmails") String code) throws Exception {

        Harness h = newHarness();

        h.mockMvc.perform(post("/api/auth/otp/verify")
                        .contentType("application/json")
                        .content(verifyBody(email, code)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(h.authService);
    }
}
