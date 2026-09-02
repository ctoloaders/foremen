package com.foremen.controller;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.controller.dto.auth.TokenResponse;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint-level validation tests for the invite endpoints added to {@link AuthController}
 * (Feature: FOR-03-02-user-invitation).
 *
 * <p>Covers (example/boundary cases; the exhaustive Property 10 for Requirement 5.3 lives in
 * {@link SetPasswordRequestValidationPropertyTest}):
 * <ul>
 *   <li>{@code POST /api/auth/set-password} and {@code POST /api/auth/resend-invite} are reachable
 *       at their declared paths (Requirements 5.1, 6.1).</li>
 *   <li>Blank/missing token or password length &lt;8 / &gt;72 &rarr; HTTP 400 with no token lookup;
 *       the 8- and 72-char boundaries reach the service (Requirement 5.3).</li>
 *   <li>Null/missing {@code userId} &rarr; HTTP 400 with no user lookup (Requirement 6.3).</li>
 * </ul>
 *
 * <p>Setup: a standalone MockMvc wiring the real {@link AuthController} and
 * {@link ForemenControllerAdvice} (so {@code @Valid} failures surface as HTTP 400 exactly as in
 * production) with mocked {@link AuthService} and {@link InviteService}. This isolates the
 * controller's validation contract from the security filter chain and the database — the mocks let
 * each test assert that the service is NEVER called when validation fails (i.e. no token/user
 * lookup happens), and IS called when the request is well-formed.
 */
class AuthControllerInviteValidationTest {

    private AuthService authService;
    private InviteService inviteService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        inviteService = mock(InviteService.class);

        MessageResolver messageResolver = mock(MessageResolver.class);
        when(messageResolver.resolve(anyString(), any(), any(Locale.class)))
                .thenReturn("validation error");

        AuthController controller = new AuthController(authService, inviteService);
        ForemenControllerAdvice advice = new ForemenControllerAdvice(messageResolver);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .build();
    }

    private static String setPasswordBody(String tokenJson, String passwordJson) {
        return "{\"token\":" + tokenJson + ",\"password\":" + passwordJson + "}";
    }

    private static String jsonString(String value) {
        return "\"" + value + "\"";
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }

    // ---------------------------------------------------------------------
    // Reachability (Requirements 5.1, 6.1)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/auth/set-password is reachable and reaches AuthService for a valid body (5.1)")
    void setPasswordReachableForValidBody() throws Exception {
        when(authService.setPassword(anyString(), anyString()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(setPasswordBody(jsonString("valid-token"), jsonString("password123"))))
                .andExpect(status().isOk());

        verify(authService, times(1)).setPassword("valid-token", "password123");
    }

    @Test
    @DisplayName("POST /api/auth/resend-invite is reachable and reaches InviteService for a valid body (6.1)")
    void resendInviteReachableForValidBody() throws Exception {
        mockMvc.perform(post("/api/auth/resend-invite")
                        .contentType("application/json")
                        .content("{\"userId\":42}"))
                .andExpect(status().isOk());

        verify(inviteService, times(1)).resend(42L);
    }

    // ---------------------------------------------------------------------
    // set-password: blank token (Requirement 5.3)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("set-password with blank token → 400 and no AuthService lookup (5.3)")
    void setPasswordBlankTokenRejectedBeforeLookup() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(setPasswordBody(jsonString("   "), jsonString("password123"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("set-password with empty token → 400 and no AuthService lookup (5.3)")
    void setPasswordEmptyTokenRejectedBeforeLookup() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(setPasswordBody(jsonString(""), jsonString("password123"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("set-password with missing token field → 400 and no AuthService lookup (5.3)")
    void setPasswordMissingTokenRejectedBeforeLookup() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content("{\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    // ---------------------------------------------------------------------
    // set-password: password length boundaries (Requirement 5.3)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("set-password with 7-char password → 400 and no AuthService lookup (5.3)")
    void setPasswordTooShortRejectedBeforeLookup() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(setPasswordBody(jsonString("valid-token"), jsonString(repeat('a', 7)))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("set-password with 73-char password → 400 and no AuthService lookup (5.3)")
    void setPasswordTooLongRejectedBeforeLookup() throws Exception {
        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(setPasswordBody(jsonString("valid-token"), jsonString(repeat('a', 73)))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("set-password with 8-char password (lower bound) reaches AuthService (5.3 boundary)")
    void setPasswordMinLengthReachesService() throws Exception {
        when(authService.setPassword(anyString(), anyString()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(setPasswordBody(jsonString("valid-token"), jsonString(repeat('a', 8)))))
                .andExpect(status().isOk());

        verify(authService, times(1)).setPassword("valid-token", repeat('a', 8));
    }

    @Test
    @DisplayName("set-password with 72-char password (upper bound) reaches AuthService (5.3 boundary)")
    void setPasswordMaxLengthReachesService() throws Exception {
        when(authService.setPassword(anyString(), anyString()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        mockMvc.perform(post("/api/auth/set-password")
                        .contentType("application/json")
                        .content(setPasswordBody(jsonString("valid-token"), jsonString(repeat('a', 72)))))
                .andExpect(status().isOk());

        verify(authService, times(1)).setPassword("valid-token", repeat('a', 72));
    }

    // ---------------------------------------------------------------------
    // resend-invite: null userId (Requirement 6.3)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("resend-invite with null userId → 400 and no InviteService lookup (6.3)")
    void resendInviteNullUserIdRejectedBeforeLookup() throws Exception {
        mockMvc.perform(post("/api/auth/resend-invite")
                        .contentType("application/json")
                        .content("{\"userId\":null}"))
                .andExpect(status().isBadRequest());

        verify(inviteService, never()).resend(anyLong());
    }

    @Test
    @DisplayName("resend-invite with missing userId field → 400 and no InviteService lookup (6.3)")
    void resendInviteMissingUserIdRejectedBeforeLookup() throws Exception {
        mockMvc.perform(post("/api/auth/resend-invite")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(inviteService, never()).resend(anyLong());
    }

}
