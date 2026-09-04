package com.foremen.controller;

import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.LoginRequest;
import com.foremen.controller.dto.auth.OtpRequestRequest;
import com.foremen.controller.dto.auth.OtpVerifyRequest;
import com.foremen.controller.dto.auth.PasswordResetConfirm;
import com.foremen.controller.dto.auth.PasswordResetRequest;
import com.foremen.controller.dto.auth.RefreshRequest;
import com.foremen.controller.dto.auth.ResendInviteRequest;
import com.foremen.controller.dto.auth.SetPasswordRequest;
import com.foremen.controller.dto.auth.TokenResponse;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import com.foremen.service.OtpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the authentication endpoints under {@code /api/auth}
 * (Requirements 3, 7, 8, 9, 13).
 *
 * <p>Request bodies are validated with {@code @Valid}, so blank/missing fields (and a too-short
 * new password) are rejected with HTTP 400 by the existing
 * {@code MethodArgumentNotValidException} handler before any business logic runs (Requirements
 * 3.2, 13.7). All business logic is delegated to {@link AuthService}; this controller only maps
 * HTTP concerns (paths, methods, status codes).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final InviteService inviteService;
    private final OtpService otpService;

    /**
     * Authenticates a user and issues an access/refresh token pair (Requirement 3.1).
     *
     * @return HTTP 200 with a {@link TokenResponse}
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(authService.login(request.email(), request.password()));
    }

    /**
     * Rotates a refresh token, issuing a fresh access/refresh pair (Requirement 7.2).
     *
     * @return HTTP 200 with a {@link TokenResponse}
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody @Valid RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    /**
     * Revokes the supplied refresh token (Requirement 8.1). Idempotent — returns HTTP 204 whether
     * or not the token was known.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @Valid RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    /**
     * Returns the identity and derived permissions of the authenticated user (Requirement 9.1).
     *
     * <p>The principal is the user id ({@code sub} claim) established by
     * {@code JwtAuthenticationFilter}; access to this endpoint requires a valid access token, and
     * an unauthenticated request is rejected with HTTP 401 by the security layer (Requirement 9.3).
     *
     * @return HTTP 200 with a {@link CurrentUserResponse}
     */
    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(authService.currentUser(userId));
    }

    /**
     * Requests a password reset for the given email (Requirement 13.1). Always returns HTTP 200 to
     * avoid disclosing whether the email exists (anti-enumeration, Requirement 13.3).
     */
    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.OK)
    public void requestPasswordReset(@RequestBody @Valid PasswordResetRequest request) {
        authService.requestPasswordReset(request.email());
    }

    /**
     * Confirms a password reset with a token and a new password (Requirement 13.4). A too-short new
     * password is rejected with HTTP 400 by validation (Requirement 13.7).
     */
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.OK)
    public void confirmPasswordReset(@RequestBody @Valid PasswordResetConfirm request) {
        authService.confirmPasswordReset(request.token(), request.newPassword());
    }

    /**
     * Sets the password for an invited user via a valid invite token, activating the account and
     * immediately issuing an access/refresh token pair (auto-login, Requirements 5.1, 5.4, 5.5).
     *
     * <p>{@code @Valid} rejects a blank token or a password outside 8..72 characters with HTTP 400
     * through the existing {@code MethodArgumentNotValidException} handler, before any token lookup
     * (Requirement 5.3).
     *
     * @return HTTP 200 with a {@link TokenResponse}
     */
    @PostMapping("/set-password")
    public ResponseEntity<TokenResponse> setPassword(@RequestBody @Valid SetPasswordRequest request) {
        return ResponseEntity.ok(authService.setPassword(request.token(), request.password()));
    }

    /**
     * Resends an invitation for the target user, rotating the invite token and dispatching a fresh
     * invitation email (Requirements 6.1, 6.6). Restricted to ADMIN callers by the security layer.
     *
     * <p>{@code @Valid} rejects a null {@code userId} with HTTP 400 through the existing handler,
     * before any user lookup (Requirement 6.3).
     */
    @PostMapping("/resend-invite")
    @ResponseStatus(HttpStatus.OK)
    public void resendInvite(@RequestBody @Valid ResendInviteRequest request) {
        inviteService.resend(request.userId());
    }

    /**
     * Requests a passwordless OTP login code for the given client email (Requirement 4.1). Always
     * returns HTTP 200 to avoid disclosing whether the email is an eligible client (Silent_Success,
     * anti-enumeration, Requirement 4.4); rate limiting and eligibility are handled by
     * {@link OtpService#request(String)}.
     *
     * <p>{@code @Valid} rejects a blank or missing {@code email} with HTTP 400 through the existing
     * {@code MethodArgumentNotValidException} handler, before any rate-limit check or user lookup
     * (Requirement 4.2).
     */
    @PostMapping("/otp/request")
    @ResponseStatus(HttpStatus.OK)
    public void otpRequest(@RequestBody @Valid OtpRequestRequest request) {
        otpService.request(request.email());
    }

    /**
     * Verifies an OTP code and, on success, issues a client-TTL access/refresh token pair
     * (Requirements 6.1, 6.3).
     *
     * <p>{@code @Valid} rejects a blank or missing {@code email} or {@code code} with HTTP 400
     * through the existing handler, before any code lookup (Requirement 6.2).
     *
     * @return HTTP 200 with a {@link TokenResponse}
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<TokenResponse> otpVerify(@RequestBody @Valid OtpVerifyRequest request) {
        return ResponseEntity.ok(authService.verifyOtp(request.email(), request.code()));
    }
}
