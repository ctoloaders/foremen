package com.foremen.service.property;

import java.util.Optional;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.GoogleLoginResponse;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import com.foremen.service.OtpService;
import com.foremen.service.RefreshTokenService;
import com.foremen.service.mail.MailSender;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Google login token/status invariant in
 * {@link AuthService#loginWithGoogle(String)} (Requirement 14).
 *
 * <p>Each iteration wires a fresh {@link AuthService} over a Mockito-mocked {@link UserDao} whose
 * {@link UserDao#findByEmail(String)} returns a generated user of an arbitrary status (or empty for
 * the not-found case). The {@link GoogleIdTokenVerifier} is stubbed to return a verified token
 * carrying a fixed email, so verification always succeeds and the branch is driven purely by the
 * looked-up account's status. The {@link JwtTokenProvider} is real (constructed from a
 * {@link JwtProperties} with a &ge;32-byte secret) so the ACTIVE path issues a genuine access
 * token, and {@link RefreshTokenService} / {@link InviteService} are mocked so the ACTIVE and
 * INVITED branches produce a refresh token and a set-password token respectively.
 *
 * <p>The universal invariant asserted for every generated case is
 * {@code tokens != null} <em>if and only if</em> {@code status.equals("AUTHENTICATED")}: an ACTIVE
 * account yields {@code AUTHENTICATED} with a non-null {@code tokens} and a null
 * {@code setPasswordToken}; an INVITED account yields {@code ACTIVATION_REQUIRED} with a null
 * {@code tokens} and a non-null {@code setPasswordToken}. DEACTIVATED and not-found are outside the
 * token-invariant scope (they throw), but the test also confirms they never produce a
 * tokens-bearing response.
 *
 * Property 9: The Google login response carries tokens only for ACTIVE accounts (backend)
 * — Validates: Requirements 14.4, 14.5, 14.14
 */
@Tag("Feature: FOR-03-06-frontend-auth, Property 9: The Google login response carries tokens only for ACTIVE accounts (backend)")
class GoogleLoginResponseInvariantPropertyTest {

    /** &ge;32 bytes so the real JwtTokenProvider can derive an HS256 signing key. */
    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /** Cost factor 12, matching PasswordEncoderConfig / production. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    /** The verified email the stubbed Google verifier reports for every token. */
    private static final String VERIFIED_EMAIL = "google-user@example.com";

    /** The raw set-password token the stubbed InviteService mints for INVITED accounts. */
    private static final String MINTED_SET_PASSWORD_TOKEN = "minted-set-password-token";

    /**
     * Builds an {@link AuthService} whose Google verifier always resolves {@code VERIFIED_EMAIL}
     * and whose {@link UserDao#findByEmail(String)} returns {@code userForLookup} (a {@code null}
     * entity models the not-found case). {@link RefreshTokenService#issue(UserEntity)} yields a
     * refresh token and {@link InviteService#mintSetPasswordToken(UserEntity)} yields a fixed
     * set-password token so the ACTIVE / INVITED branches are fully exercised.
     */
    private static AuthService authService(UserEntity userForLookup) {
        UserDao userDao = Mockito.mock(UserDao.class);
        when(userDao.findByEmail(any()))
                .thenReturn(Optional.ofNullable(userForLookup));

        RefreshTokenService refreshTokenService = Mockito.mock(RefreshTokenService.class);
        when(refreshTokenService.issue(any(UserEntity.class))).thenReturn("refresh-token-value");

        InviteService inviteService = Mockito.mock(InviteService.class);
        when(inviteService.mintSetPasswordToken(any(UserEntity.class)))
                .thenReturn(MINTED_SET_PASSWORD_TOKEN);

        // Build the verified token first (its own nested stubbing must complete) before stubbing
        // the verifier, so the two Mockito when(...) chains do not interleave.
        GoogleIdToken verified = verifiedToken(VERIFIED_EMAIL);
        GoogleIdTokenVerifier verifier = Mockito.mock(GoogleIdTokenVerifier.class);
        try {
            when(verifier.verify(any(String.class))).thenReturn(verified);
        } catch (Exception e) {
            throw new IllegalStateException("stubbing GoogleIdTokenVerifier.verify failed", e);
        }

        PasswordResetTokenDao passwordResetTokenDao = Mockito.mock(PasswordResetTokenDao.class);
        MailSender mailSender = Mockito.mock(MailSender.class);
        InviteTokenDao inviteTokenDao = Mockito.mock(InviteTokenDao.class);
        OtpService otpService = Mockito.mock(OtpService.class);

        JwtProperties props = new JwtProperties(30, 7, SECRET);
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(props);

        return new AuthService(
                userDao, ENCODER, jwtTokenProvider, refreshTokenService, props,
                passwordResetTokenDao, mailSender, inviteService, inviteTokenDao, otpService,
                verifier);
    }

    /** A {@link GoogleIdToken} whose payload reports {@code email} as the verified address. */
    private static GoogleIdToken verifiedToken(String email) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail(email);
        GoogleIdToken token = Mockito.mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(payload);
        return token;
    }

    private static UserEntity user(long id, String email, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName("user-" + id);
        user.setEmail(email);
        user.setStatus(status);
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode("WORKER");
        user.setRole(role);
        return user;
    }

    // Feature: FOR-03-06-frontend-auth, Property 9: The Google login response carries tokens only
    // for ACTIVE accounts (backend).
    // For every verified Google email resolving to an ACTIVE or INVITED account, the response holds
    // the invariant `tokens != null` iff `status == AUTHENTICATED`: ACTIVE -> AUTHENTICATED with a
    // non-null tokens and a null setPasswordToken; INVITED -> ACTIVATION_REQUIRED with a null tokens
    // and a non-null setPasswordToken.
    // Validates: Requirements 14.4, 14.5, 14.14
    @Property(tries = 100)
    void tokensPresentIffAuthenticated(@ForAll("userIds") long id,
                                       @ForAll("activeOrInvited") UserStatus status) {
        UserEntity account = user(id, VERIFIED_EMAIL, status);
        AuthService authService = authService(account);

        GoogleLoginResponse response = authService.loginWithGoogle("any-google-id-token");

        boolean authenticated =
                GoogleLoginResponse.STATUS_AUTHENTICATED.equals(response.status());

        // Core invariant: tokens present if and only if the status is AUTHENTICATED.
        assertThat(response.tokens() != null)
                .as("tokens != null must hold iff status == AUTHENTICATED (status=%s)",
                        response.status())
                .isEqualTo(authenticated);

        if (status == UserStatus.ACTIVE) {
            assertThat(response.status()).isEqualTo(GoogleLoginResponse.STATUS_AUTHENTICATED);
            assertThat(response.tokens()).as("ACTIVE -> non-null tokens").isNotNull();
            assertThat(response.tokens().accessToken())
                    .as("ACTIVE -> issued access token").isNotBlank();
            assertThat(response.tokens().refreshToken())
                    .as("ACTIVE -> issued refresh token").isNotBlank();
            assertThat(response.setPasswordToken())
                    .as("ACTIVE -> null setPasswordToken").isNull();
        } else {
            assertThat(response.status())
                    .isEqualTo(GoogleLoginResponse.STATUS_ACTIVATION_REQUIRED);
            assertThat(response.tokens()).as("INVITED -> null tokens").isNull();
            assertThat(response.setPasswordToken())
                    .as("INVITED -> non-null setPasswordToken").isNotNull();
            assertThat(response.setPasswordToken())
                    .as("INVITED -> non-empty setPasswordToken").isNotBlank();
        }
    }

    // DEACTIVATED and not-found are outside the token-invariant scope: they throw a
    // ForemenApiException rather than returning a response, and therefore can never yield a
    // tokens-bearing response.
    // Validates: Requirements 14.4, 14.14 (negative guard)
    @Property(tries = 100)
    void nonSessionAccountsNeverYieldTokens(@ForAll("userIds") long id,
                                            @ForAll("deactivatedOrMissing") boolean deactivated) {
        UserEntity account = deactivated ? user(id, VERIFIED_EMAIL, UserStatus.DEACTIVATED) : null;
        AuthService authService = authService(account);

        assertThatThrownBy(() -> authService.loginWithGoogle("any-google-id-token"))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // --- Providers ---

    /** User ids: positive longs across a broad range. */
    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    /** The two session-relevant statuses that yield a GoogleLoginResponse. */
    @Provide
    Arbitrary<UserStatus> activeOrInvited() {
        return Arbitraries.of(UserStatus.ACTIVE, UserStatus.INVITED);
    }

    /** true -> a DEACTIVATED account, false -> no account (not found). */
    @Provide
    Arbitrary<Boolean> deactivatedOrMissing() {
        return Arbitraries.of(true, false);
    }
}
