package com.foremen.controller;

import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.GoogleLoginRequest;
import com.foremen.controller.dto.auth.GoogleLoginResponse;
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
import com.foremen.util.MeETag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
     * Exchanges a verified Google ID token for the application session (Requirements 14.3, 14.4,
     * 14.5). Delegates to {@link AuthService#loginWithGoogle(String)}, which verifies the token,
     * links it to an existing account by verified email, and returns one of two HTTP 200 outcomes:
     * {@code AUTHENTICATED} (an ACTIVE account, carrying the JWT pair) or {@code ACTIVATION_REQUIRED}
     * (an INVITED account, carrying a freshly-minted set-password token and no session). Both are
     * HTTP 200 because Google verification itself succeeded; the discriminated
     * {@link GoogleLoginResponse} lets the client branch on {@code status} without inferring intent
     * from null fields (14.4, 14.5, 14.14). The unlinked and DEACTIVATED cases are surfaced as HTTP
     * 403 by {@link AuthService}.
     *
     * <p>{@code @Valid} rejects a blank or missing {@code idToken} with HTTP 400 through the existing
     * {@code MethodArgumentNotValidException} handler, before any token verification.
     *
     * <p>This endpoint is publicly reachable: it is matched by the broad {@code /api/auth/**}
     * {@code permitAll} rule in {@code SecurityConfig} and is NOT caught by the more specific
     * {@code /api/auth/me} ({@code authenticated()}) or {@code POST /api/auth/resend-invite}
     * ({@code hasRole("ADMIN")}) matchers declared before it. Verified: no new security matcher is
     * needed for {@code POST /api/auth/google}, so an unauthenticated caller reaches the controller
     * rather than receiving a security 401.
     *
     * @return HTTP 200 with a {@link GoogleLoginResponse} for both AUTHENTICATED and
     *         ACTIVATION_REQUIRED
     */
    @PostMapping("/google")
    public ResponseEntity<GoogleLoginResponse> google(@RequestBody @Valid GoogleLoginRequest request) {
        return ResponseEntity.ok(authService.loginWithGoogle(request.idToken()));
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
     * <p>Supports conditional requests (Requirements 13.1–13.4): a strong {@link MeETag} is
     * computed from the live role graph returned by {@link AuthService#currentUser(Long)} (not the
     * Caffeine permission cache). When the inbound {@code If-None-Match} matches the current ETag,
     * the endpoint returns HTTP 304 with an empty body; otherwise HTTP 200 with the DTO. Both
     * responses carry the ETag and {@code Cache-Control: no-cache, private} so the client always
     * revalidates via {@code If-None-Match}.
     *
     * @return HTTP 200 with a {@link CurrentUserResponse}, or HTTP 304 when unchanged
     */
    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        CurrentUserResponse dto = authService.currentUser(userId);
        String etag = MeETag.compute(dto);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache().cachePrivate())
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(dto);
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
