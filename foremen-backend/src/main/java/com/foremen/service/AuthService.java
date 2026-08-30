package com.foremen.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.PermissionView;
import com.foremen.controller.dto.auth.TokenResponse;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
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
                       MailSender mailSender) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenService = refreshTokenService;
        this.jwtProperties = jwtProperties;
        this.passwordResetTokenDao = passwordResetTokenDao;
        this.mailSender = mailSender;
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
        if (user.getStatus() != UserStatus.ACTIVE) {
            return;
        }

        PasswordResetTokenEntity resetToken = new PasswordResetTokenEntity();
        String tokenValue = generateResetTokenValue();
        resetToken.setToken(tokenValue);
        resetToken.setUser(user);
        resetToken.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL));
        resetToken.setUsed(false);
        passwordResetTokenDao.save(resetToken);

        mailSender.sendPasswordReset(user.getEmail(), tokenValue);
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
