package com.foremen.service.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.LoginRequest;
import com.foremen.controller.dto.auth.TokenResponse;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
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
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for {@link AuthService#login(String, String)} (Requirement 3).
 *
 * <p>Each test wires a fresh {@link AuthService} over a Mockito-mocked {@link UserDao} (so the
 * email lookup is fully controlled) and a real {@link BCryptPasswordEncoder} at cost factor 12,
 * matching production. The {@link JwtTokenProvider} is real (constructed from a
 * {@link JwtProperties} with a ≥32-byte secret) so the success path issues a genuine token, and
 * the timing-attack property exercises real bcrypt work. {@link RefreshTokenService},
 * {@link PasswordResetTokenDao}, and {@link MailSender} are mocked — of these only
 * {@code RefreshTokenService.issue} participates in the login success path.
 *
 * <p>Property 7 is enforced at the controller boundary via jakarta bean validation on
 * {@link LoginRequest} (@NotBlank), not inside {@code AuthService.login}. It is therefore tested
 * at the DTO/validation level with a {@link Validator}, asserting @NotBlank violations for blank
 * email/password so the request is rejected (HTTP 400) before any credential verification runs.
 *
 * Property 7: Login rejects blank credential fields before verification — Validates: Requirements 3.2
 * Property 8: Login rejects non-matching credentials with 401 — Validates: Requirements 3.4, 3.5
 * Property 9: Login failure timing is bounded (≤100ms median) — Validates: Requirements 3.6
 * Property 10: INVITED users cannot log in (403) — Validates: Requirements 3.7
 * Property 11: DEACTIVATED users cannot log in (403) — Validates: Requirements 3.8
 * Property 12: Login expiresIn matches configured lifetime × 60 — Validates: Requirements 3.9
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 7-12: AuthService.login")
class AuthServiceLoginPropertyTest {

    /** ≥32 bytes so the real JwtTokenProvider can derive an HS256 signing key. */
    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /** Cost factor 12, matching PasswordEncoderConfig / Requirement 10.1. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    private final Validator validator;

    AuthServiceLoginPropertyTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    /**
     * Builds an {@link AuthService} whose {@link UserDao#findByEmail(String)} returns
     * {@code userSupplier} for any email argument. A {@code null} entity models the no-user case.
     */
    private static AuthService authService(int accessTtlMinutes, UserEntity userForLookup) {
        UserDao userDao = Mockito.mock(UserDao.class);
        when(userDao.findByEmail(any()))
                .thenReturn(Optional.ofNullable(userForLookup));

        RefreshTokenService refreshTokenService = Mockito.mock(RefreshTokenService.class);
        when(refreshTokenService.issue(any(UserEntity.class))).thenReturn("refresh-token-value");

        PasswordResetTokenDao passwordResetTokenDao = Mockito.mock(PasswordResetTokenDao.class);
        MailSender mailSender = Mockito.mock(MailSender.class);

        JwtProperties props = new JwtProperties(accessTtlMinutes, 7, SECRET);
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(props);

        return new AuthService(
                userDao, ENCODER, jwtTokenProvider, refreshTokenService,
                props, passwordResetTokenDao, mailSender, null, null, null, null);
    }

    private static UserEntity user(long id, String email, UserStatus status, String rawPassword) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName("user-" + id);
        user.setEmail(email);
        user.setStatus(status);
        user.setPasswordHash(rawPassword == null ? null : ENCODER.encode(rawPassword));
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode("WORKER");
        user.setRole(role);
        return user;
    }

    // Feature: FOR-03-01-jwt-auth, Property 7: Login rejects blank credential fields before verification
    // For all login requests where email or password is missing, empty, or whitespace-only, the
    // endpoint responds 400 and performs no credential verification. Enforced by @NotBlank on
    // LoginRequest; tested at the validation level so a blank field yields a violation on that
    // field before AuthService.login is ever reached.
    // Validates: Requirements 3.2
    @Property(tries = 100)
    void blankEmailIsRejected(@ForAll("blankStrings") String blankEmail,
                              @ForAll("nonBlankStrings") String password) {
        LoginRequest request = new LoginRequest(blankEmail, password);

        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("blank email must yield a @NotBlank violation on 'email'")
                .anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Property(tries = 100)
    void blankPasswordIsRejected(@ForAll("nonBlankStrings") String email,
                                 @ForAll("blankStrings") String blankPassword) {
        LoginRequest request = new LoginRequest(email, blankPassword);

        Set<ConstraintViolation<LoginRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("blank password must yield a @NotBlank violation on 'password'")
                .anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    // Feature: FOR-03-01-jwt-auth, Property 8: Login rejects non-matching credentials with 401
    // For all login attempts where the email matches no user, or the password does not match the
    // stored bcrypt hash of an ACTIVE user, login raises a 401 error.auth.invalid.credentials.
    // Validates: Requirements 3.4, 3.5
    @Property(tries = 100)
    void unknownEmailRejectedWith401(@ForAll("emails") String email,
                                     @ForAll("nonBlankStrings") String password) {
        AuthService authService = authService(30, null);

        assertThatThrownBy(() -> authService.login(email, password))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.invalid.credentials");
                });
    }

    @Property(tries = 100)
    void wrongPasswordRejectedWith401(@ForAll("userIds") long id,
                                      @ForAll("emails") String email,
                                      @ForAll("distinctPasswords") Password pair) {
        UserEntity activeUser = user(id, email.toLowerCase(), UserStatus.ACTIVE, pair.stored());
        AuthService authService = authService(30, activeUser);

        assertThatThrownBy(() -> authService.login(email, pair.attempt()))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.invalid.credentials");
                });
    }

    // Feature: FOR-03-01-jwt-auth, Property 9: Login failure timing is bounded (timing-attack resistance)
    // For all pairs of a no-user login and a wrong-password login, the measured response-time
    // difference is at most 100 ms (median over iterations), achieved by comparing against a fixed
    // dummy bcrypt hash in the no-user case. This is a single aggregate property (not @Property):
    // it measures medians across many iterations, which is inherently a cross-sample statistic.
    // Validates: Requirements 3.6
    @org.junit.jupiter.api.Test
    void failureTimingIsBounded() {
        String password = "correct-horse-battery-staple";
        UserEntity activeUser = user(1L, "known@example.com", UserStatus.ACTIVE, password);

        AuthService noUserService = authService(30, null);
        AuthService wrongPasswordService = authService(30, activeUser);

        int iterations = 20;
        // Warm up the JIT and bcrypt paths so timing is representative. bcrypt at cost 12 is
        // deliberately expensive (~hundreds of ms), so iteration counts are kept modest.
        for (int i = 0; i < 3; i++) {
            safeLogin(noUserService, "missing@example.com", "whatever");
            safeLogin(wrongPasswordService, "known@example.com", "not-the-password");
        }

        List<Long> noUserTimes = new ArrayList<>(iterations);
        List<Long> wrongPasswordTimes = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            noUserTimes.add(measureNanos(() ->
                    safeLogin(noUserService, "missing@example.com", "whatever")));
            wrongPasswordTimes.add(measureNanos(() ->
                    safeLogin(wrongPasswordService, "known@example.com", "not-the-password")));
        }

        double noUserMedianMs = medianMillis(noUserTimes);
        double wrongPasswordMedianMs = medianMillis(wrongPasswordTimes);
        double differenceMs = Math.abs(noUserMedianMs - wrongPasswordMedianMs);

        assertThat(differenceMs)
                .as("median failure timing of no-user vs wrong-password paths (no-user=%.2fms, "
                        + "wrong-password=%.2fms) must differ by at most 100ms",
                        noUserMedianMs, wrongPasswordMedianMs)
                .isLessThanOrEqualTo(100.0);
    }

    // Feature: FOR-03-01-jwt-auth, Property 10: INVITED users cannot log in (403)
    // For all users whose status is INVITED and any password, login raises a 403
    // error.auth.account.not.activated.
    // Validates: Requirements 3.7
    @Property(tries = 100)
    void invitedUsersCannotLogIn(@ForAll("userIds") long id,
                                 @ForAll("emails") String email,
                                 @ForAll("nonBlankStrings") String password) {
        UserEntity invited = user(id, email.toLowerCase(), UserStatus.INVITED, password);
        AuthService authService = authService(30, invited);

        assertThatThrownBy(() -> authService.login(email, password))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.account.not.activated");
                });
    }

    // Feature: FOR-03-01-jwt-auth, Property 11: DEACTIVATED users cannot log in (403)
    // For all users whose status is DEACTIVATED and any password, login raises a 403
    // error.auth.account.deactivated.
    // Validates: Requirements 3.8
    @Property(tries = 100)
    void deactivatedUsersCannotLogIn(@ForAll("userIds") long id,
                                     @ForAll("emails") String email,
                                     @ForAll("nonBlankStrings") String password) {
        UserEntity deactivated = user(id, email.toLowerCase(), UserStatus.DEACTIVATED, password);
        AuthService authService = authService(30, deactivated);

        assertThatThrownBy(() -> authService.login(email, password))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.account.deactivated");
                });
    }

    // Feature: FOR-03-01-jwt-auth, Property 12: Login expiresIn matches configured lifetime × 60
    // For all positive access-token lifetimes (minutes), a successful login response's expiresIn
    // equals that lifetime multiplied by 60.
    // Validates: Requirements 3.9
    @Property(tries = 100)
    void expiresInMatchesConfiguredLifetime(@ForAll("userIds") long id,
                                            @ForAll("emails") String email,
                                            @ForAll @IntRange(min = 1, max = 100_000) int accessTtlMinutes) {
        String password = "matching-password-" + id;
        UserEntity activeUser = user(id, email.toLowerCase(), UserStatus.ACTIVE, password);
        AuthService authService = authService(accessTtlMinutes, activeUser);

        TokenResponse response = authService.login(email, password);

        assertThat(response.accessToken()).as("success must return an access token").isNotBlank();
        assertThat(response.refreshToken()).as("success must return a refresh token").isNotBlank();
        assertThat(response.expiresIn())
                .as("expiresIn must equal accessTtlMinutes * 60")
                .isEqualTo((long) accessTtlMinutes * 60);
    }

    // --- Helpers ---

    private static void safeLogin(AuthService service, String email, String password) {
        try {
            service.login(email, password);
        } catch (ForemenApiException ignored) {
            // Failure is expected on both timing paths; we only measure duration.
        }
    }

    private static long measureNanos(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private static double medianMillis(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        long medianNanos = (n % 2 == 1)
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        return medianNanos / 1_000_000.0;
    }

    /** A stored password and a distinct attempt password (guaranteed unequal). */
    record Password(String stored, String attempt) {}

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

    /** Non-blank strings usable as passwords. */
    @Provide
    Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(1)
                .ofMaxLength(40);
    }

    /** Blank strings: empty and whitespace-only (space, tab, newline). */
    @Provide
    Arbitrary<String> blankStrings() {
        Arbitrary<String> whitespace = Arbitraries.strings()
                .withChars(' ', '\t', '\n', '\r')
                .ofMinLength(1)
                .ofMaxLength(6);
        return Arbitraries.oneOf(Arbitraries.just(""), whitespace);
    }

    /** A stored password and a distinct wrong-attempt password. */
    @Provide
    Arbitrary<Password> distinctPasswords() {
        Arbitrary<String> stored = nonBlankStrings();
        Arbitrary<String> attempt = nonBlankStrings();
        return Combinators.combine(stored, attempt)
                .as(Password::new)
                .filter(p -> !p.stored().equals(p.attempt()));
    }
}
