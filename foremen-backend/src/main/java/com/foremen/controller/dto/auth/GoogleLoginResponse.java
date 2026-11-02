package com.foremen.controller.dto.auth;

/**
 * Response for {@code POST /api/auth/google}.
 *
 * <p>Since FOR-03-10 every successful Google sign-in yields {@code AUTHENTICATED} with the issued
 * JWT pair: ACTIVE employees are signed in and INVITED employees are auto-activated. The
 * {@code setPasswordToken} component is kept only for wire compatibility and is always
 * {@code null}; failures are signalled with HTTP 401/403 errors instead.
 */
public record GoogleLoginResponse(
    String status,             // always "AUTHENTICATED"
    TokenResponse tokens,      // always non-null
    String setPasswordToken    // always null (legacy field, kept for wire compatibility)
) {

    public static final String STATUS_AUTHENTICATED = "AUTHENTICATED";

    /** Build an {@code AUTHENTICATED} response carrying the issued JWT pair. */
    public static GoogleLoginResponse authenticated(TokenResponse tokens) {
        return new GoogleLoginResponse(STATUS_AUTHENTICATED, tokens, null);
    }
}
