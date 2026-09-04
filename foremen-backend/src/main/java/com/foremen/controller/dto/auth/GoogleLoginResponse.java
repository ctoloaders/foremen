package com.foremen.controller.dto.auth;

/**
 * Discriminated response for {@code POST /api/auth/google}.
 *
 * <ul>
 *   <li>{@code AUTHENTICATED}      — {@code tokens} populated, {@code setPasswordToken} null
 *       (verified Google email linked to an ACTIVE account).</li>
 *   <li>{@code ACTIVATION_REQUIRED} — {@code tokens} null, {@code setPasswordToken} populated
 *       (verified Google email linked to an INVITED account; no session is issued and the
 *       account must still set a password to become ACTIVE).</li>
 * </ul>
 *
 * <p>Security invariant: no access/refresh token is ever present when
 * {@code status != AUTHENTICATED} — i.e. {@code tokens != null} if and only if
 * {@code status.equals("AUTHENTICATED")}. Both outcomes return HTTP 200 because Google
 * verification succeeded in both cases.
 */
public record GoogleLoginResponse(
    String status,             // "AUTHENTICATED" | "ACTIVATION_REQUIRED"
    TokenResponse tokens,      // non-null iff AUTHENTICATED
    String setPasswordToken    // non-null iff ACTIVATION_REQUIRED
) {

    public static final String STATUS_AUTHENTICATED = "AUTHENTICATED";
    public static final String STATUS_ACTIVATION_REQUIRED = "ACTIVATION_REQUIRED";

    /**
     * Build an {@code AUTHENTICATED} response for a linked ACTIVE account, carrying the
     * issued JWT pair and no set-password token.
     */
    public static GoogleLoginResponse authenticated(TokenResponse tokens) {
        return new GoogleLoginResponse(STATUS_AUTHENTICATED, tokens, null);
    }

    /**
     * Build an {@code ACTIVATION_REQUIRED} response for a linked INVITED account, carrying
     * a freshly-minted set-password token and no session tokens.
     */
    public static GoogleLoginResponse activationRequired(String setPasswordToken) {
        return new GoogleLoginResponse(STATUS_ACTIVATION_REQUIRED, null, setPasswordToken);
    }
}
