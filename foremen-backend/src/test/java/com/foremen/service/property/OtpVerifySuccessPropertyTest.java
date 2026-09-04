package com.foremen.service.property;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.TokenResponse;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OtpTokenEntity;
import com.foremen.dao.model.RefreshTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import com.foremen.service.OtpCodeGenerator;
import com.foremen.service.OtpService;
import com.foremen.service.RefreshTokenService;
import com.foremen.service.mail.MailSender;
import com.foremen.service.mail.OtpMailSender;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Property-based test for FOR-03-05-otp-client-auth, Property 7: verify success marks the code
 * used and issues client-TTL tokens (Requirements 6.3, 6.9).
 *
 * <p>Property statement: for all persisted {@link OtpTokenEntity} rows that are unused, unexpired,
 * with {@code attempts} less than 3, whose email is an Eligible_Email, submitting the matching
 * code SHALL set the entity {@code used} to true and return a {@link TokenResponse} whose access
 * token and refresh token are non-blank and whose {@code expiresIn} equals the configured client
 * access TTL in minutes multiplied by 60.
 *
 * <p>This property spans two collaborators: {@link OtpService#verifyCode(String, String)} performs
 * the state-machine walk and, on success, marks the token {@code used} and returns the
 * authenticated ACTIVE CLIENT user; {@link AuthService#verifyOtp(String, String)} then issues the
 * client-TTL access/refresh pair and computes {@code expiresIn}. The test therefore wires a real
 * {@link OtpService}, a real {@link JwtTokenProvider}, a real {@link RefreshTokenService} (over an
 * in-memory {@link RefreshTokenDao} mock, mirroring {@link ClientTtlSelectionPropertyTest}), and a
 * real {@link AuthService}, following the existing OtpService property-test conventions. Only the
 * collaborators {@code verifyOtp} never touches are mocked with defaults.
 *
 * <p>The client access TTL is generated freely so {@code expiresIn == clientAccessTtlMinutes * 60}
 * is exercised across many configurations, and the stored token's {@code used} flag is asserted
 * both on the captured saved entity and on the in-memory row.
 *
 * Property 7: Verify success marks the code used and issues client-TTL tokens — Validates: Requirements 6.3, 6.9
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 7: Verify success marks code used and issues client-TTL tokens")
class OtpVerifySuccessPropertyTest {

    private static final String CLIENT_ROLE_CODE = "CLIENT";

    private static final String SECRET = "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /**
     * A tiny in-memory store backing the mocked {@link RefreshTokenDao}, assigning sequential ids
     * on save — enough for {@link RefreshTokenService#issue(UserEntity, int)}.
     */
    private static final class InMemoryStore {
        private final Map<Long, RefreshTokenEntity> byId = new ConcurrentHashMap<>();
        private final AtomicLong sequence = new AtomicLong(0);

        RefreshTokenEntity save(RefreshTokenEntity entity) {
            if (entity.getId() == null) {
                entity.setId(sequence.incrementAndGet());
            }
            byId.put(entity.getId(), entity);
            return entity;
        }
    }

    /** The full wiring needed to drive {@link AuthService#verifyOtp(String, String)}. */
    private record Fixture(AuthService authService,
                           OtpTokenDao otpTokenDao,
                           UserDao userDao,
                           int clientAccessTtlMinutes) {}

    /** Builds the real service graph over mocked DAOs for the given client TTL configuration. */
    private static Fixture fixture(int clientAccessTtlMinutes, int clientRefreshTtlDays) {
        JwtProperties props = new JwtProperties(
                30, 7, clientAccessTtlMinutes, clientRefreshTtlDays, SECRET);

        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(props);

        InMemoryStore store = new InMemoryStore();
        RefreshTokenDao refreshTokenDao = Mockito.mock(RefreshTokenDao.class);
        when(refreshTokenDao.save(any(RefreshTokenEntity.class)))
                .thenAnswer(inv -> store.save(inv.getArgument(0)));
        RefreshTokenService refreshTokenService = new RefreshTokenService(refreshTokenDao, props);

        OtpTokenDao otpTokenDao = Mockito.mock(OtpTokenDao.class);
        // save returns its argument so verifyCode can continue operating on the entity.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDao userDao = Mockito.mock(UserDao.class);
        OtpMailSender otpMailSender = Mockito.mock(OtpMailSender.class);
        OtpCodeGenerator codeGenerator = Mockito.mock(OtpCodeGenerator.class);
        OtpService otpService = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);

        // Collaborators verifyOtp never touches: default Mockito behavior is sufficient.
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        PasswordResetTokenDao passwordResetTokenDao = Mockito.mock(PasswordResetTokenDao.class);
        MailSender mailSender = Mockito.mock(MailSender.class);
        InviteService inviteService = Mockito.mock(InviteService.class);
        InviteTokenDao inviteTokenDao = Mockito.mock(InviteTokenDao.class);

        AuthService authService = new AuthService(
                userDao, passwordEncoder, jwtTokenProvider, refreshTokenService, props,
                passwordResetTokenDao, mailSender, inviteService, inviteTokenDao, otpService, null);

        // The verify path resolves the user by email (OtpService confirms Eligible_Email before
        // consuming the code); the userDao stub is wired per-property once the email is known.
        return new Fixture(authService, otpTokenDao, userDao, clientAccessTtlMinutes);
    }

    /** An ACTIVE CLIENT user — the only shape that is an Eligible_Email. */
    private static UserEntity eligibleClient(long id, String email) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName("client-" + id);
        user.setEmail(email.toLowerCase());
        user.setStatus(UserStatus.ACTIVE);
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(CLIENT_ROLE_CODE);
        user.setRole(role);
        return user;
    }

    /** A fresh, valid token: unused, unexpired, attempts &lt; 3, with the given code. */
    private static OtpTokenEntity validToken(String email, String code, int attempts) {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail(email);
        token.setCode(code);
        token.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        token.setUsed(false);
        token.setAttempts(attempts);
        return token;
    }

    // Feature: FOR-03-05-otp-client-auth, Property 7: Verify success marks the code used and issues client-TTL tokens
    // For all persisted OtpTokenEntity rows that are unused, unexpired, with attempts < 3, whose
    // email is an Eligible_Email, submitting the matching code SHALL set the entity used to true
    // and return a TokenResponse whose access and refresh tokens are non-blank and whose
    // expiresIn equals the configured client access TTL in minutes * 60.
    // Validates: Requirements 6.3, 6.9
    @Property(tries = 200)
    void verifySuccessMarksUsedAndIssuesClientTtlTokens(
            @ForAll("userIds") long id,
            @ForAll("emails") String email,
            @ForAll("codes") String code,
            @ForAll("attemptsBelowMax") int attempts,
            @ForAll("ttlMinutes") int clientAccessTtlMinutes,
            @ForAll("ttlDays") int clientRefreshTtlDays) {

        Fixture fx = fixture(clientAccessTtlMinutes, clientRefreshTtlDays);

        UserEntity client = eligibleClient(id, email);
        OtpTokenEntity token = validToken(email, code, attempts);

        // The OtpService verify lookup returns the newest token regardless of used/expired state;
        // the eligibility confirmation then resolves the same email to the ACTIVE CLIENT user.
        when(fx.userDao().findByEmail(anyString())).thenReturn(Optional.of(client));
        when(fx.otpTokenDao().findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString()))
                .thenReturn(Optional.of(token));

        TokenResponse response = fx.authService().verifyOtp(email, code);

        // (6.3) The matched token is marked used=true and persisted.
        ArgumentCaptor<OtpTokenEntity> savedCaptor = ArgumentCaptor.forClass(OtpTokenEntity.class);
        Mockito.verify(fx.otpTokenDao()).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().isUsed())
                .as("a successful verification marks the matched token used=true")
                .isTrue();
        assertThat(token.isUsed())
                .as("the persisted token entity reflects used=true")
                .isTrue();

        // (6.3) Non-blank access and refresh tokens are returned.
        assertThat(response.accessToken())
                .as("access token is non-blank on verify success")
                .isNotBlank();
        assertThat(response.refreshToken())
                .as("refresh token is non-blank on verify success")
                .isNotBlank();

        // (6.9) expiresIn equals the configured client access TTL in minutes * 60.
        assertThat(response.expiresIn())
                .as("expiresIn equals the client access TTL in seconds")
                .isEqualTo((long) fx.clientAccessTtlMinutes() * 60);
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

    /** Exactly six decimal digits, leading zeros preserved. */
    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.integers().between(0, 999_999).map(v -> String.format("%06d", v));
    }

    /** Attempt counts strictly below the max-attempts threshold (0, 1, 2). */
    @Provide
    Arbitrary<Integer> attemptsBelowMax() {
        return Arbitraries.integers().between(0, 2);
    }

    /** Client access-token lifetimes in minutes (bounded to keep expiry arithmetic in range). */
    @Provide
    Arbitrary<Integer> ttlMinutes() {
        return Arbitraries.integers().between(1, 1440);
    }

    /** Client refresh-token lifetimes in days. */
    @Provide
    Arbitrary<Integer> ttlDays() {
        return Arbitraries.integers().between(1, 365);
    }
}
