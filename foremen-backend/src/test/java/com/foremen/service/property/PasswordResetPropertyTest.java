package com.foremen.service.property;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.PasswordResetConfirm;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PasswordResetTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AuthService;
import com.foremen.service.RefreshTokenService;
import com.foremen.service.mail.MailSender;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.LongRange;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for the {@link AuthService} password-reset flow
 * ({@code requestPasswordReset} and {@code confirmPasswordReset}, Requirement 13).
 *
 * <p>Each test wires a fresh {@link AuthService} over Mockito-mocked {@link UserDao},
 * {@link PasswordResetTokenDao}, {@link RefreshTokenService}, {@link JwtTokenProvider} (unused by
 * the reset paths), a mocked {@link MailSender}, and a real {@link BCryptPasswordEncoder} at cost
 * factor 12 (matching production) so hash/verify assertions exercise genuine bcrypt work.
 * {@link JwtProperties} is constructed directly.
 *
 * <p>Property 30 is enforced at the controller boundary via jakarta bean validation
 * ({@code @Size(min = 8)} on {@link PasswordResetConfirm#newPassword()}), not inside the service.
 * It is therefore tested at the DTO/validation level with a {@link Validator}, asserting a
 * {@code @Size} violation on {@code newPassword} for passwords shorter than 8 characters so the
 * request is rejected (HTTP 400) before the service is reached.
 *
 * Property 26: Reset request issues a token only for ACTIVE users — Validates: Requirements 13.2
 * Property 27: Reset request does not enumerate users — Validates: Requirements 13.3
 * Property 28: Reset confirm sets the hash and revokes refresh tokens — Validates: Requirements 13.5, 13.8
 * Property 29: Reset confirm rejects invalid tokens (400) — Validates: Requirements 13.6
 * Property 30: Reset confirm enforces minimum password length (400) — Validates: Requirements 13.7
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 26-30: AuthService password reset")
class PasswordResetPropertyTest {

    /** >=32 bytes so the (unused) real JwtTokenProvider can derive an HS256 signing key. */
    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /** Cost factor 12, matching PasswordEncoderConfig / Requirement 10.1. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    private final Validator validator;

    PasswordResetPropertyTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    /**
     * All collaborators of an {@link AuthService} under test, so a property can drive DAO answers
     * and verify interactions.
     */
    private record Fixture(AuthService service,
                           UserDao userDao,
                           PasswordResetTokenDao passwordResetTokenDao,
                           RefreshTokenService refreshTokenService,
                           MailSender mailSender) {}

    private static Fixture fixture() {
        UserDao userDao = Mockito.mock(UserDao.class);
        PasswordResetTokenDao passwordResetTokenDao = Mockito.mock(PasswordResetTokenDao.class);
        RefreshTokenService refreshTokenService = Mockito.mock(RefreshTokenService.class);
        MailSender mailSender = Mockito.mock(MailSender.class);

        // save returns its argument so the service can continue operating on the entity.
        when(userDao.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordResetTokenDao.save(any(PasswordResetTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JwtProperties props = new JwtProperties(30, 7, SECRET);
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(props);

        AuthService service = new AuthService(
                userDao, ENCODER, jwtTokenProvider, refreshTokenService,
                props, passwordResetTokenDao, mailSender);
        return new Fixture(service, userDao, passwordResetTokenDao, refreshTokenService, mailSender);
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

    // Feature: FOR-03-01-jwt-auth, Property 26: Reset request issues a token only for ACTIVE users
    // For all ACTIVE users, requesting a password reset persists a single-use, unused
    // PasswordResetTokenEntity owned by that user with an expiry ~60 minutes in the future, and
    // sends a reset email to the user's address.
    // Validates: Requirements 13.2
    @Property(tries = 100)
    void activeUserGetsTokenAndEmail(@ForAll("userIds") long id,
                                     @ForAll("emails") String email) {
        Fixture fx = fixture();
        UserEntity active = user(id, email.toLowerCase(), UserStatus.ACTIVE);
        when(fx.userDao().findByEmail(anyString())).thenReturn(Optional.of(active));

        Instant before = Instant.now();
        fx.service().requestPasswordReset(email);
        Instant after = Instant.now();

        ArgumentCaptor<PasswordResetTokenEntity> tokenCaptor =
                ArgumentCaptor.forClass(PasswordResetTokenEntity.class);
        verify(fx.passwordResetTokenDao()).save(tokenCaptor.capture());
        PasswordResetTokenEntity saved = tokenCaptor.getValue();

        assertThat(saved.getToken()).as("issued reset token value must be non-blank").isNotBlank();
        assertThat(saved.isUsed()).as("a fresh reset token must be unused").isFalse();
        assertThat(saved.getUser()).as("reset token must be owned by the requesting user").isEqualTo(active);

        Instant lower = before.plusSeconds(60 * 60 - 2);
        Instant upper = after.plusSeconds(60 * 60 + 2);
        assertThat(saved.getExpiresAt())
                .as("reset token expiry must be within tolerance of now + 60 minutes")
                .isAfterOrEqualTo(lower)
                .isBeforeOrEqualTo(upper);

        verify(fx.mailSender()).sendPasswordReset(active.getEmail(), saved.getToken());
    }

    // Feature: FOR-03-01-jwt-auth, Property 27: Reset request does not enumerate users
    // For all reset requests referencing no user, an INVITED user, or a DEACTIVATED user, the
    // service persists no token and sends no email, so a caller cannot tell whether the email
    // exists (anti-enumeration).
    // Validates: Requirements 13.3
    @Property(tries = 100)
    void nonActiveOrUnknownUserYieldsNoTokenAndNoEmail(@ForAll("userIds") long id,
                                                       @ForAll("emails") String email,
                                                       @ForAll("nonIssuingLookup") Optional<UserStatus> maybeStatus) {
        Fixture fx = fixture();
        Optional<UserEntity> lookup = maybeStatus.map(status -> user(id, email.toLowerCase(), status));
        when(fx.userDao().findByEmail(anyString())).thenReturn(lookup);

        fx.service().requestPasswordReset(email);

        verify(fx.passwordResetTokenDao(), never()).save(any(PasswordResetTokenEntity.class));
        verify(fx.mailSender(), never()).sendPasswordReset(anyString(), anyString());
    }

    // Feature: FOR-03-01-jwt-auth, Property 28: Reset confirm sets the hash and revokes refresh tokens
    // For all valid (unused, unexpired) reset tokens and new passwords, confirming the reset sets
    // the user's passwordHash to a bcrypt hash matching the new password, marks the token used, and
    // revokes all of the user's refresh tokens.
    // Validates: Requirements 13.5, 13.8
    @Property(tries = 100)
    void confirmSetsHashMarksTokenUsedAndRevokesRefreshTokens(@ForAll("userIds") long id,
                                                              @ForAll("emails") String email,
                                                              @ForAll("newPasswords") String newPassword,
                                                              @ForAll("tokenValues") String tokenValue) {
        Fixture fx = fixture();
        UserEntity user = user(id, email.toLowerCase(), UserStatus.ACTIVE);

        PasswordResetTokenEntity resetToken = new PasswordResetTokenEntity();
        resetToken.setToken(tokenValue);
        resetToken.setUser(user);
        resetToken.setExpiresAt(Instant.now().plusSeconds(30 * 60));
        resetToken.setUsed(false);
        when(fx.passwordResetTokenDao().findByToken(tokenValue)).thenReturn(Optional.of(resetToken));

        fx.service().confirmPasswordReset(tokenValue, newPassword);

        assertThat(user.getPasswordHash())
                .as("password hash must be set on confirm").isNotBlank();
        assertThat(ENCODER.matches(newPassword, user.getPasswordHash()))
                .as("stored hash must verify against the new password").isTrue();
        assertThat(resetToken.isUsed()).as("consumed token must be marked used").isTrue();
        verify(fx.userDao()).save(user);
        verify(fx.refreshTokenService(), times(1)).revokeAllForUser(user.getId());
    }

    // Feature: FOR-03-01-jwt-auth, Property 29: Reset confirm rejects invalid tokens (400)
    // For all confirm requests whose token does not exist, is already used, or has expired, the
    // service raises a 400 error.auth.reset.token.invalid and does not change any password or
    // revoke any refresh tokens.
    // Validates: Requirements 13.6
    @Property(tries = 100)
    void confirmRejectsInvalidTokens(@ForAll("userIds") long id,
                                     @ForAll("emails") String email,
                                     @ForAll("newPasswords") String newPassword,
                                     @ForAll("tokenValues") String tokenValue,
                                     @ForAll("invalidTokenKind") InvalidTokenKind kind,
                                     @ForAll @LongRange(min = 1, max = 1_000_000) long expiredSecondsAgo) {
        Fixture fx = fixture();

        switch (kind) {
            case UNKNOWN -> when(fx.passwordResetTokenDao().findByToken(tokenValue))
                    .thenReturn(Optional.empty());
            case USED -> {
                PasswordResetTokenEntity used = new PasswordResetTokenEntity();
                used.setToken(tokenValue);
                used.setUser(user(id, email.toLowerCase(), UserStatus.ACTIVE));
                used.setExpiresAt(Instant.now().plusSeconds(30 * 60));
                used.setUsed(true);
                when(fx.passwordResetTokenDao().findByToken(tokenValue)).thenReturn(Optional.of(used));
            }
            case EXPIRED -> {
                PasswordResetTokenEntity expired = new PasswordResetTokenEntity();
                expired.setToken(tokenValue);
                expired.setUser(user(id, email.toLowerCase(), UserStatus.ACTIVE));
                expired.setExpiresAt(Instant.now().minusSeconds(expiredSecondsAgo));
                expired.setUsed(false);
                when(fx.passwordResetTokenDao().findByToken(tokenValue)).thenReturn(Optional.of(expired));
            }
        }

        assertThatThrownBy(() -> fx.service().confirmPasswordReset(tokenValue, newPassword))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.reset.token.invalid");
                });

        verify(fx.userDao(), never()).save(any(UserEntity.class));
        verify(fx.refreshTokenService(), never()).revokeAllForUser(any());
    }

    // Feature: FOR-03-01-jwt-auth, Property 30: Reset confirm enforces minimum password length (400)
    // For all new passwords shorter than 8 characters, bean validation on PasswordResetConfirm
    // yields a @Size violation on newPassword, so the controller rejects the request (400) before
    // the service runs. Tested at the validation level.
    // Validates: Requirements 13.7
    @Property(tries = 100)
    void shortNewPasswordYieldsSizeViolation(@ForAll("tokenValues") String token,
                                             @ForAll("shortPasswords") String shortPassword) {
        PasswordResetConfirm request = new PasswordResetConfirm(token, shortPassword);

        Set<ConstraintViolation<PasswordResetConfirm>> violations = validator.validate(request);

        assertThat(violations)
                .as("a password shorter than 8 chars must yield a violation on 'newPassword'")
                .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
    }

    // --- Providers ---

    /** User ids: positive longs across a broad range. */
    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    /** Realistic-ish email addresses (mixed case to exercise case-insensitive lookup). */
    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('A', 'Z', '0', '9')
                .ofMinLength(1)
                .ofMaxLength(20);
        return local.map(l -> l + "@example.com");
    }

    /**
     * The non-issuing lookup outcomes for a reset request: no user
     * ({@code Optional.empty()}), an INVITED user, or a DEACTIVATED user.
     */
    @Provide
    Arbitrary<Optional<UserStatus>> nonIssuingLookup() {
        return Arbitraries.of(
                Optional.<UserStatus>empty(),
                Optional.of(UserStatus.INVITED),
                Optional.of(UserStatus.DEACTIVATED));
    }

    /** Non-blank opaque token values. */
    @Provide
    Arbitrary<String> tokenValues() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(1)
                .ofMaxLength(48);
    }

    /** Valid new passwords (>= 8 chars, matching the policy). */
    @Provide
    Arbitrary<String> newPasswords() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(8)
                .ofMaxLength(40);
    }

    /** Passwords shorter than the 8-character minimum (including empty). */
    @Provide
    Arbitrary<String> shortPasswords() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(0)
                .ofMaxLength(7);
    }

    /** The three ways a reset token can be invalid at confirm time. */
    enum InvalidTokenKind { UNKNOWN, USED, EXPIRED }

    @Provide
    Arbitrary<InvalidTokenKind> invalidTokenKind() {
        return Arbitraries.of(InvalidTokenKind.values());
    }
}
