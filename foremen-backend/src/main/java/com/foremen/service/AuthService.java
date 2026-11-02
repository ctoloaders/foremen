package com.foremen.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.GoogleLoginResponse;
import com.foremen.controller.dto.auth.PermissionView;
import com.foremen.controller.dto.auth.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PasswordResetTokenEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.mail.MailSender;

/**
 * Encapsulates authentication business logic: credential verification and token issuance
 * (Requirement 3), and — added by later tasks — refresh/logout/current-user (7, 8, 9) and
 * password reset (13).
 *
 * <p>All collaborators are injected via the constructor. The full set required by the auth
 * flows is wired up front so subsequent tasks can add the remaining methods without changing
 * the constructor.
 */
@Slf4j
@Service
public class AuthService {

    /** Password-reset tokens are single-use and expire 60 minutes after issuance (Requirement 13.2). */
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(60);

    /** 256 bits of randomness for the opaque password-reset token value. */
    private static final int RESET_TOKEN_BYTES = 32;

    private final UserDao userDao;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;
    private final PasswordResetTokenDao passwordResetTokenDao;
    private final MailSender mailSender;
    private final InviteService inviteService;
    private final InviteTokenDao inviteTokenDao;
    private final OtpService otpService;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * A real bcrypt hash of a random value computed once at startup. The no-user login path
     * verifies the supplied password against this hash so an unknown-email request and a
     * wrong-password request take equivalent time, resisting timing-based user enumeration
     * (Requirement 3.6).
     */
    private final String dummyHash;

    public AuthService(UserDao userDao,
                       BCryptPasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       RefreshTokenService refreshTokenService,
                       JwtProperties jwtProperties,
                       PasswordResetTokenDao passwordResetTokenDao,
                       MailSender mailSender,
                       InviteService inviteService,
                       InviteTokenDao inviteTokenDao,
                       OtpService otpService,
                       GoogleIdTokenVerifier googleIdTokenVerifier) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.passwordResetTokenDao = passwordResetTokenDao;
        this.mailSender = mailSender;
        this.inviteService = inviteService;
        this.inviteTokenDao = inviteTokenDao;
        this.otpService = otpService;
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.dummyHash = passwordEncoder.encode(randomSecret());
    }

    /**
     * Authenticates a user by email and password and, on success, issues an access token and a
     * refresh token (Requirement 3).
     *
     * <p>Behavior:
     * <ul>
     *   <li>Email lookup is case-insensitive (3.3, 3.4).</li>
     *   <li>Unknown email: the password is still verified against a fixed dummy hash to equalize
     *       timing (3.6), then a 401 {@code error.auth.invalid.credentials} is raised (3.4).</li>
     *   <li>INVITED account: 403 {@code error.auth.account.not.activated} (3.7).</li>
     *   <li>DEACTIVATED account: 403 {@code error.auth.account.deactivated} (3.8).</li>
     *   <li>ACTIVE account with a non-matching password: 401 {@code error.auth.invalid.credentials} (3.5).</li>
     *   <li>ACTIVE account with a matching password: access + refresh tokens, with
     *       {@code expiresIn = accessTtlMinutes * 60} (3.3, 3.9).</li>
     * </ul>
     */
    @Transactional
    public TokenResponse login(String email, String rawPassword) {
        Optional<UserEntity> maybeUser = userDao.findByEmail(email.toLowerCase());

        if (maybeUser.isEmpty()) {
            // No such user: run a real bcrypt verification against the dummy hash so the
            // response time matches the wrong-password path (timing-attack resistance, 3.6).
            passwordEncoder.matches(rawPassword, dummyHash);
            throw invalidCredentials();
        }

        UserEntity user = maybeUser.get();

        switch (user.getStatus()) {
            case INVITED -> throw new ForemenApiException(
                    HttpStatus.FORBIDDEN, "error.auth.account.not.activated");
            case DEACTIVATED -> throw new ForemenApiException(
                    HttpStatus.FORBIDDEN, "error.auth.account.deactivated");
            case ACTIVE -> {
                if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
                    throw invalidCredentials();
                }
                return issueTokens(user);
            }
            default -> throw invalidCredentials();
        }
    }

    /**
     * Refreshes an access/refresh token pair using an existing refresh token (Requirement 7).
     *
     * <p>Delegates validation and one-time-use rotation to {@link RefreshTokenService#rotate(String)},
     * which revokes the supplied token and returns its owning entity, or raises a 401 with the
     * appropriate message code when the token is unknown ({@code error.auth.refresh.invalid}, 7.4),
     * revoked ({@code error.auth.refresh.revoked}, 7.5), or expired
     * ({@code error.auth.refresh.expired}, 7.6). On success a fresh access token and a fresh refresh
     * token are issued for the owner (7.3, 7.7).
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        UserEntity owner = refreshTokenService.rotate(refreshToken).getUser();
        return issueTokens(owner);
    }

    /**
     * Logs a user out by revoking the supplied refresh token (Requirement 8).
     *
     * <p>Idempotent: an unknown token is a no-op and raises no error (8.3). A known token is
     * revoked so it can no longer be used for refresh (8.2, 8.4).
     */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /**
     * Returns the identity and derived permissions of the given user (Requirement 9).
     *
     * <p>The response carries the user's id, name, email, and role code (9.2). Permissions are
     * derived from the role's resource-operation associations: for each
     * {@link RoleResourceEntity} the resource code is paired with the set of its operation codes
     * (9.4).
     *
     * @throws ForemenApiException 404 {@code error.entity.not.found} if no user matches {@code userId}
     */
    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(Long userId) {
        UserEntity user = userDao.findById(userId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, "error.entity.not.found", userId));

        Set<PermissionView> permissions = new LinkedHashSet<>();
        for (RoleResourceEntity roleResource : user.getRole().getRoleResources()) {
            String resourceCode = roleResource.getResource().getCode();
            Set<String> operations = new LinkedHashSet<>();
            for (OperationEntity operation : roleResource.getOperations()) {
                operations.add(operation.getCode());
            }
            permissions.add(new PermissionView(resourceCode, operations));
        }

        return new CurrentUserResponse(
                user.getId(), user.getName(), user.getEmail(), user.getRole().getCode(), permissions);
    }

    /**
     * Requests a password reset for the account identified by {@code email} (Requirement 13).
     *
     * <p>Only an ACTIVE user receives a reset token: a single-use {@link PasswordResetTokenEntity}
     * (TTL 60 minutes, {@code used=false}) is persisted and a reset email is sent to the user's
     * address (13.2). For every other case — no matching user, or a user whose status is INVITED
     * or DEACTIVATED — nothing is persisted and no email is sent, so the caller cannot tell whether
     * the email exists (anti-enumeration, 13.3). The method always returns silently.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        Optional<UserEntity> maybeUser = userDao.findByEmail(email.toLowerCase());
        if (maybeUser.isEmpty()) {
            return;
        }

        UserEntity user = maybeUser.get();
        // FOR-03-09: only ACTIVE password accounts may reset; CLIENT users sign in via OTP and get
        // the same neutral outcome as an unknown email (no token, no mail).
        if (user.getStatus() != UserStatus.ACTIVE || isClient(user)) {
            return;
        }

        PasswordResetTokenEntity resetToken = new PasswordResetTokenEntity();
        String tokenValue = generateResetTokenValue();
        resetToken.setToken(tokenValue);
        resetToken.setUser(user);
        resetToken.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL));
        resetToken.setUsed(false);
        passwordResetTokenDao.save(resetToken);

        // FOR-03-09: best-effort dispatch. A transport failure is logged but must not surface as a
        // 500, which would reveal that the account exists (the endpoint stays a neutral 200).
        try {
            mailSender.sendPasswordReset(user, tokenValue);
        } catch (RuntimeException e) {
            log.error("Password-reset email dispatch failed for user id={}", user.getId(), e);
        }
    }

    private static boolean isClient(UserEntity user) {
        return user.getRole() != null && "CLIENT".equals(user.getRole().getCode());
    }

    /**
     * Confirms a password reset, setting a new password for the token owner (Requirement 13).
     *
     * <p>The supplied token must exist, be unused, and not have expired; otherwise a 400
     * {@code error.auth.reset.token.invalid} is raised (13.6). On success the user's
     * {@code passwordHash} is set to the bcrypt hash of {@code newPassword}, the token is marked
     * used, and all of the user's refresh tokens are revoked so no existing session survives the
     * password change (13.5, 13.8).
     *
     * @throws ForemenApiException 400 {@code error.auth.reset.token.invalid} if the token is unknown,
     *                             already used, or expired
     */
    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        PasswordResetTokenEntity resetToken = passwordResetTokenDao.findByToken(token)
                .filter(t -> !t.isUsed())
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.BAD_REQUEST, "error.auth.reset.token.invalid"));

        UserEntity user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userDao.save(user);

        resetToken.setUsed(true);
        passwordResetTokenDao.save(resetToken);

        refreshTokenService.revokeAllForUser(user.getId());
    }

    /**
     * Activates an invited account by setting its password from a valid invite token and, on
     * success, immediately logs the user in by issuing an access/refresh token pair (Requirement 5).
     *
     * <p>Token validation is delegated to {@link InviteService#consume(String)}, which enforces the
     * invalid &rarr; used &rarr; expired &rarr; owner-status ordering and raises the appropriate
     * {@link ForemenApiException} for each negative branch (5.6&ndash;5.8, 5.10). When the token is
     * valid and the owner is INVITED, all writes happen in this single {@code @Transactional}
     * method so activation is atomic (5.4):
     * <ul>
     *   <li>the owner's {@code passwordHash} is set to the bcrypt hash (cost 12) of
     *       {@code rawPassword};</li>
     *   <li>the owner's status is set to {@link UserStatus#ACTIVE};</li>
     *   <li>the invite token is marked {@code used = true}, so any later set-password with the same
     *       value hits the {@code error.invite.token.used} branch (5.9).</li>
     * </ul>
     * On completion an access token and a refresh token are returned for the owner via
     * {@link #issueTokens(UserEntity)}, reusing the FOR-03-01 {@link JwtTokenProvider} and
     * {@link RefreshTokenService} (5.5).
     *
     * @param token       the raw invite-token value from the set-password request
     * @param rawPassword the new password to hash and store
     * @return the access/refresh token pair for the now-active user
     * @throws ForemenApiException per {@link InviteService#consume(String)}
     */
    @Transactional
    public TokenResponse setPassword(String token, String rawPassword) {
        InviteTokenEntity invite = inviteService.consume(token);

        UserEntity user = invite.getUser();
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.ACTIVE);
        userDao.save(user);

        invite.setUsed(true);
        inviteTokenDao.save(invite);

        return issueTokens(user);
    }

    /**
     * Completes an OTP login by verifying the submitted code and issuing a client-TTL token pair
     * (Requirements 6.3, 6.9, 7.3).
     *
     * <p>Verification is delegated to {@link OtpService#verifyCode(String, String)}, which walks
     * the fixed OTP state machine and, on success, marks the code used and returns the
     * authenticated ACTIVE CLIENT user (raising the appropriate {@link ForemenApiException} for
     * every negative branch). Tokens are then issued with the <strong>client</strong> lifetimes
     * from {@link JwtProperties} — the client access TTL for the access token and the client
     * refresh TTL for the refresh token — independently of the employee TTLs (7.3). The returned
     * {@link TokenResponse} reports {@code expiresIn} as the client access TTL in seconds
     * ({@code clientAccessTtlMinutes * 60}) per Requirement 6.9.
     *
     * @param email the client email
     * @param code  the submitted OTP code
     * @return the client-TTL access/refresh token pair
     * @throws ForemenApiException per {@link OtpService#verifyCode(String, String)}
     */
    @Transactional
    public TokenResponse verifyOtp(String email, String code) {
        UserEntity user = otpService.verifyCode(email, code);

        int accessTtlMinutes = jwtProperties.clientAccessTtlMinutes();
        int refreshTtlDays = jwtProperties.clientRefreshTtlDays();

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getRole().getCode(), user.getEmail(), accessTtlMinutes);
        String refreshToken = refreshTokenService.issue(user, refreshTtlDays);
        long expiresIn = (long) accessTtlMinutes * 60;

        return new TokenResponse(accessToken, refreshToken, expiresIn);
    }

    /**
     * Exchanges a verified Google ID token for an application session (FOR-03-10).
     *
     * <p>Rules, in order:
     * <ol>
     *   <li>The token is verified (signature, audience = {@code foremen.google.client-id}, expiry);
     *       any failure &rarr; {@code 401 error.auth.google.token.invalid}.</li>
     *   <li>The {@code email_verified} claim must be {@code true}; otherwise
     *       {@code 401 error.auth.google.email.unverified} (an unverified address could belong to
     *       someone else, so it must never be matched to a Foremen account).</li>
     *   <li>The user is looked up by the exact (lower-cased) token email &mdash; no linking of a Google
     *       account to a different email. Not found &rarr; {@code 403 error.auth.google.no.account}.</li>
     *   <li>CLIENT users sign in only with the email OTP &rarr; {@code 403 error.auth.google.client}.</li>
     *   <li>ACTIVE &rarr; session. INVITED &rarr; auto-activated (Google's verified email is trusted:
     *       status ACTIVE, outstanding invite tokens marked used, password hash untouched) and a session
     *       is issued. DEACTIVATED &rarr; {@code 403 error.auth.account.deactivated}.</li>
     * </ol>
     *
     * @param idToken the raw Google ID token from the browser's Google Identity Services flow
     * @return always an {@code AUTHENTICATED} response carrying the JWT pair
     */
    @Transactional
    public GoogleLoginResponse loginWithGoogle(String idToken) {
        GoogleIdToken verifiedToken = verifyGoogleIdToken(idToken);
        GoogleIdToken.Payload payload = verifiedToken.getPayload();
        String email = payload.getEmail();
        if (email == null || email.isBlank()) {
            throw googleTokenInvalid();
        }
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.google.email.unverified");
        }

        UserEntity user = userDao.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.FORBIDDEN, "error.auth.google.no.account"));

        if (isClient(user)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.auth.google.client");
        }

        return switch (user.getStatus()) {
            case ACTIVE -> GoogleLoginResponse.authenticated(issueTokens(user));
            case INVITED -> {
                activateViaGoogle(user);
                yield GoogleLoginResponse.authenticated(issueTokens(user));
            }
            case DEACTIVATED -> throw new ForemenApiException(
                    HttpStatus.FORBIDDEN, "error.auth.account.deactivated");
        };
    }

    /**
     * Activates an INVITED employee on their first Google sign-in: status becomes ACTIVE and every
     * outstanding invite token is consumed so an old invitation link can no longer be used. The
     * password hash is left as is (the user may set one later via the password-reset flow).
     */
    private void activateViaGoogle(UserEntity user) {
        user.setStatus(UserStatus.ACTIVE);
        userDao.save(user);
        for (InviteTokenEntity invite : inviteTokenDao.findByUserIdAndUsedFalse(user.getId())) {
            invite.setUsed(true);
            inviteTokenDao.save(invite);
        }
        log.info("User id={} activated via Google sign-in", user.getId());
    }

    /**
     * Verifies a Google ID token with the configured verifier, normalizing every failure mode
     * (invalid signature/audience/expiry, a malformed token, or an I/O error contacting Google's
     * certificate endpoint) into a single {@code 401 error.auth.google.token.invalid}.
     */
    private GoogleIdToken verifyGoogleIdToken(String idToken) {
        GoogleIdToken verifiedToken;
        try {
            verifiedToken = googleIdTokenVerifier.verify(idToken);
        } catch (Exception e) {
            throw googleTokenInvalid();
        }
        if (verifiedToken == null) {
            throw googleTokenInvalid();
        }
        return verifiedToken;
    }

    private static ForemenApiException googleTokenInvalid() {
        return new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.google.token.invalid");
    }

    private TokenResponse issueTokens(UserEntity user) {
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getRole().getCode(), user.getEmail());
        String refreshToken = refreshTokenService.issue(user);
        long expiresIn = (long) jwtProperties.accessTtlMinutes() * 60;
        return new TokenResponse(accessToken, refreshToken, expiresIn);
    }

    private static ForemenApiException invalidCredentials() {
        return new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.invalid.credentials");
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateResetTokenValue() {
        byte[] bytes = new byte[RESET_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
