package com.foremen.service.property;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OtpTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OtpCodeGenerator;
import com.foremen.service.OtpService;
import com.foremen.service.mail.OtpMailSender;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property 5: Rate limit is threshold-based, pre-lookup, and non-enumerating.
 *
 * <p>For all emails and all prior in-window request counts {@code n}, and for both eligible and
 * non-eligible emails, {@link OtpService#request(String)} SHALL be rejected with HTTP 429 and
 * message code {@code error.auth.otp.rate.limited} if and only if {@code n >= 5}, evaluating the
 * count before any user lookup, performing no user lookup, no code generation, no persistence, and
 * no email dispatch when rejected, and producing an outcome identical for eligible and
 * non-eligible emails; when {@code n < 5} it SHALL proceed to the eligibility branch.
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked collaborators (an
 * {@link OtpTokenDao} whose {@code countByEmailIgnoreCaseAndCreatedDateAfter} returns the generated
 * in-window count {@code n}, a {@link UserDao}, an {@link OtpMailSender}, and a real
 * {@link OtpCodeGenerator}). The mocks let each property both drive the count and verify that the
 * {@code UserDao}/persistence/mail collaborators are never touched when the request is rate-limited.
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 5.3, 5.5</b>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 5: OTP rate limit")
class OtpRateLimitPropertyTest {

    private static final String CLIENT_ROLE_CODE = "CLIENT";

    /** The fixed threshold from {@code OtpService.RATE_LIMIT_PER_HOUR}. */
    private static final int RATE_LIMIT = 5;

    /** All collaborators of an {@link OtpService} under test. */
    private record Fixture(OtpService service,
                           OtpTokenDao otpTokenDao,
                           UserDao userDao,
                           OtpMailSender otpMailSender) {}

    private static Fixture fixture() {
        OtpTokenDao otpTokenDao = Mockito.mock(OtpTokenDao.class);
        UserDao userDao = Mockito.mock(UserDao.class);
        OtpMailSender otpMailSender = Mockito.mock(OtpMailSender.class);
        OtpCodeGenerator codeGenerator = new OtpCodeGenerator();

        // save returns its argument so the service can continue operating on the entity.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OtpService service = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);
        return new Fixture(service, otpTokenDao, userDao, otpMailSender);
    }

    private static UserEntity eligibleClient(String email) {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("client");
        user.setEmail(email);
        user.setStatus(UserStatus.ACTIVE);
        RoleEntity role = new RoleEntity();
        role.setId(1L);
        role.setCode(CLIENT_ROLE_CODE);
        user.setRole(role);
        return user;
    }

    /** The lookup outcome for a non-eligible email: unknown, non-CLIENT, or non-ACTIVE. */
    private static Optional<UserEntity> nonEligibleLookup(NonEligibleKind kind, String email) {
        return switch (kind) {
            case UNKNOWN -> Optional.empty();
            case NON_CLIENT -> {
                UserEntity user = eligibleClient(email);
                user.getRole().setCode("WORKER");
                yield Optional.of(user);
            }
            case NON_ACTIVE -> {
                UserEntity user = eligibleClient(email);
                user.setStatus(UserStatus.INVITED);
                yield Optional.of(user);
            }
        };
    }

    // Property 5 (rejection half): for n >= 5 the request is rejected with 429
    // error.auth.otp.rate.limited, and — identically for eligible and non-eligible emails — no user
    // lookup, no code persistence, and no email dispatch occurs.
    // Validates: Requirements 5.1, 5.2, 5.3
    @Property(tries = 200)
    void atOrAboveThresholdRejectsWithoutLookupOrSideEffects(
            @ForAll("emails") String email,
            @ForAll @IntRange(min = RATE_LIMIT, max = 20) int count,
            @ForAll boolean eligible,
            @ForAll("nonEligibleKinds") NonEligibleKind kind) {
        Fixture fx = fixture();
        when(fx.otpTokenDao().countByEmailIgnoreCaseAndCreatedDateAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn((long) count);
        // Stub the lookup for both partitions so we can prove it is never consulted when limited.
        Optional<UserEntity> lookup = eligible
                ? Optional.of(eligibleClient(email.toLowerCase()))
                : nonEligibleLookup(kind, email.toLowerCase());
        when(fx.userDao().findByEmail(anyString())).thenReturn(lookup);

        assertThatThrownBy(() -> fx.service().request(email))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.otp.rate.limited");
                });

        // (5.2) The rate limit is evaluated before the user lookup, so when rejected no lookup runs
        // at all — the 429 outcome is identical for eligible and non-eligible emails (no enumeration).
        verify(fx.userDao(), never()).findByEmail(anyString());
        // (5.3) No code persisted and no email dispatched when rate-limited.
        verify(fx.otpTokenDao(), never()).save(any(OtpTokenEntity.class));
        verify(fx.otpMailSender(), never()).send(any(UserEntity.class), anyString());
    }

    // Property 5 (pass-through half): for n < 5 the request proceeds to the eligibility branch, so
    // the user lookup IS performed and no 429 is raised.
    // Validates: Requirements 5.5
    @Property(tries = 200)
    void belowThresholdProceedsToEligibilityBranch(
            @ForAll("emails") String email,
            @ForAll @IntRange(min = 0, max = RATE_LIMIT - 1) int count,
            @ForAll boolean eligible,
            @ForAll("nonEligibleKinds") NonEligibleKind kind) {
        Fixture fx = fixture();
        when(fx.otpTokenDao().countByEmailIgnoreCaseAndCreatedDateAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn((long) count);
        Optional<UserEntity> lookup = eligible
                ? Optional.of(eligibleClient(email.toLowerCase()))
                : nonEligibleLookup(kind, email.toLowerCase());
        when(fx.userDao().findByEmail(anyString())).thenReturn(lookup);

        // Below the threshold the request never raises the rate-limit error.
        assertThatCode(() -> fx.service().request(email)).doesNotThrowAnyException();

        // (5.5) It proceeds to the eligibility branch, which performs the user lookup.
        verify(fx.userDao()).findByEmail(anyString());
        if (eligible) {
            // Eligible email within the limit: a code is persisted and an email dispatched (4.3).
            verify(fx.otpTokenDao()).save(any(OtpTokenEntity.class));
            verify(fx.otpMailSender()).send(any(UserEntity.class), anyString());
        } else {
            // Non-eligible email within the limit: Silent_Success, no persistence, no email (4.4).
            verify(fx.otpTokenDao(), never()).save(any(OtpTokenEntity.class));
            verify(fx.otpMailSender(), never()).send(any(UserEntity.class), anyString());
        }
    }

    // Property 5 (boundary): the threshold is an inclusive >= 5 gate. count == 4 passes through,
    // count == 5 is rejected. This nails the exact "if and only if n >= 5" boundary.
    // Validates: Requirements 5.1, 5.3
    @Property(tries = 50)
    void thresholdBoundaryIsInclusiveAtFive(@ForAll("emails") String email) {
        // count == RATE_LIMIT - 1 (4): proceeds (no 429), lookup happens.
        Fixture below = fixture();
        when(below.otpTokenDao().countByEmailIgnoreCaseAndCreatedDateAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn((long) (RATE_LIMIT - 1));
        when(below.userDao().findByEmail(anyString())).thenReturn(Optional.empty());
        assertThatCode(() -> below.service().request(email)).doesNotThrowAnyException();
        verify(below.userDao()).findByEmail(anyString());

        // count == RATE_LIMIT (5): rejected with 429, no lookup.
        Fixture at = fixture();
        when(at.otpTokenDao().countByEmailIgnoreCaseAndCreatedDateAfter(anyString(), any(LocalDateTime.class)))
                .thenReturn((long) RATE_LIMIT);
        when(at.userDao().findByEmail(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at.service().request(email))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(at.userDao(), never()).findByEmail(anyString());
    }

    // --- Providers ---

    /** Realistic-ish email addresses (mixed case to exercise case-insensitive handling). */
    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('A', 'Z', '0', '9')
                .ofMinLength(1)
                .ofMaxLength(20);
        return local.map(l -> l + "@example.com");
    }

    /** The three ways an in-limit email can be non-eligible. */
    enum NonEligibleKind { UNKNOWN, NON_CLIENT, NON_ACTIVE }

    @Provide
    Arbitrary<NonEligibleKind> nonEligibleKinds() {
        return Arbitraries.of(NonEligibleKind.values());
    }
}
