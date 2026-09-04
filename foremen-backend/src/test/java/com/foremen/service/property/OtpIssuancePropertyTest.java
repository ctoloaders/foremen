package com.foremen.service.property;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OtpTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.OtpCodeGenerator;
import com.foremen.service.OtpService;
import com.foremen.service.mail.OtpMailSender;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the {@link OtpService#request(String)} issuance path (Requirement 4.6).
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked {@link OtpTokenDao},
 * {@link UserDao}, {@link OtpMailSender}, and {@link OtpCodeGenerator}. The rate-limit count is
 * stubbed to zero (well within the limit) so the request always reaches the Eligible_Email
 * issuance branch, where a fresh {@link OtpTokenEntity} is persisted. The persisted entity is
 * captured with an {@link ArgumentCaptor} so its {@code expiresAt} can be asserted against the
 * issuance-plus-15-minutes expectation.
 *
 * <p>This suite covers the two issuance-path properties the design assigns to
 * {@code OtpIssuancePropertyTest}:
 * <ul>
 *   <li><b>Property 2: OTP issuance shape for eligible emails</b> — Validates: Requirements 4.3</li>
 *   <li><b>Property 3: OTP code expiry equals issuance plus 15 minutes</b> — Validates: Requirements 4.6</li>
 * </ul>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Properties 2 & 3: OTP issuance shape and expiry")
class OtpIssuancePropertyTest {

    private static final String CLIENT_ROLE_CODE = "CLIENT";

    /** Matches exactly six decimal digits with leading zeros preserved. */
    private static final String SIX_DIGITS = "^[0-9]{6}$";

    /** The fixed OTP time-to-live in minutes (OtpService.CODE_TTL_MINUTES). */
    private static final long CODE_TTL_MINUTES = 15;

    /** Tolerance applied to the expiry-instant assertion (Requirement 4.6: ±5 seconds). */
    private static final long TOLERANCE_SECONDS = 5;

    /** All collaborators of an {@link OtpService} under test. */
    private record Fixture(OtpService service,
                           OtpTokenDao otpTokenDao,
                           UserDao userDao,
                           OtpMailSender otpMailSender,
                           OtpCodeGenerator codeGenerator) {}

    private static Fixture fixture() {
        OtpTokenDao otpTokenDao = Mockito.mock(OtpTokenDao.class);
        UserDao userDao = Mockito.mock(UserDao.class);
        OtpMailSender otpMailSender = Mockito.mock(OtpMailSender.class);
        OtpCodeGenerator codeGenerator = Mockito.mock(OtpCodeGenerator.class);

        // Well within the rate limit so request() always reaches the issuance branch.
        when(otpTokenDao.countByEmailIgnoreCaseAndCreatedDateAfter(anyString(), any()))
                .thenReturn(0L);
        // save returns its argument so the service can continue operating on the entity.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // A well-formed 6-digit code so generation never influences the expiry math.
        when(codeGenerator.generate()).thenReturn("007413");

        OtpService service = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);
        return new Fixture(service, otpTokenDao, userDao, otpMailSender, codeGenerator);
    }

    /** An ACTIVE CLIENT user — the only shape that is an Eligible_Email. */
    private static UserEntity eligibleClient(long id, String email) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName("client-" + id);
        user.setEmail(email);
        user.setStatus(UserStatus.ACTIVE);
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(CLIENT_ROLE_CODE);
        user.setRole(role);
        return user;
    }

    // Feature: FOR-03-05-otp-client-auth, Property 3: OTP code expiry equals issuance plus 15 minutes
    // For all OTP codes generated at issuance time t, the persisted expiresAt SHALL be within
    // ±5 seconds of t plus 15 minutes.
    // Validates: Requirements 4.6
    @Property(tries = 100)
    void persistedExpiryIsIssuancePlusFifteenMinutes(@ForAll("userIds") long id,
                                                      @ForAll("emails") String email) {
        Fixture fx = fixture();
        UserEntity client = eligibleClient(id, email.toLowerCase());
        when(fx.userDao().findByEmail(anyString())).thenReturn(Optional.of(client));

        Instant before = Instant.now();
        fx.service().request(email);
        Instant after = Instant.now();

        ArgumentCaptor<OtpTokenEntity> tokenCaptor = ArgumentCaptor.forClass(OtpTokenEntity.class);
        Mockito.verify(fx.otpTokenDao()).save(tokenCaptor.capture());
        OtpTokenEntity saved = tokenCaptor.getValue();

        // Issuance instant t lies in [before, after]; the persisted expiry must equal t + 15m
        // within ±5s. Bound the acceptable window by both endpoints of the issuance interval.
        Instant lower = before.plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES).minusSeconds(TOLERANCE_SECONDS);
        Instant upper = after.plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES).plusSeconds(TOLERANCE_SECONDS);

        assertThat(saved.getExpiresAt())
                .as("persisted expiresAt must be within ±5s of issuance + 15 minutes")
                .isAfterOrEqualTo(lower)
                .isBeforeOrEqualTo(upper);
    }

    // Feature: FOR-03-05-otp-client-auth, Property 2: OTP issuance shape for eligible emails
    // For all Eligible_Emails (any ACTIVE user whose role code is CLIENT) requested within the
    // rate limit, request SHALL persist exactly one OtpTokenEntity for that email with used=false,
    // attempts=0, a 6-digit code, and SHALL dispatch exactly one OTP email to that address.
    // Validates: Requirements 4.3
    @Property(tries = 200)
    void eligibleEmailPersistsOneTokenAndSendsOneEmail(@ForAll("userIds") long id,
                                                       @ForAll("emails") String email) {
        Fixture fx = fixture();
        UserEntity client = eligibleClient(id, email);
        when(fx.userDao().findByEmail(anyString())).thenReturn(Optional.of(client));

        fx.service().request(email);

        // Exactly one token persisted; capture it to assert its shape (used=false, attempts=0,
        // 6-digit code, correct email).
        ArgumentCaptor<OtpTokenEntity> tokenCaptor = ArgumentCaptor.forClass(OtpTokenEntity.class);
        verify(fx.otpTokenDao(), times(1)).save(tokenCaptor.capture());
        OtpTokenEntity saved = tokenCaptor.getValue();

        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getCode()).hasSize(6).matches(SIX_DIGITS);
        assertThat(saved.getExpiresAt()).isNotNull();

        // Exactly one OTP email dispatched to that user, carrying the persisted code, and nothing
        // else on the mail sender.
        verify(fx.otpMailSender(), times(1)).send(eq(client), eq(saved.getCode()));
        verifyNoMoreInteractions(fx.otpMailSender());
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
}
