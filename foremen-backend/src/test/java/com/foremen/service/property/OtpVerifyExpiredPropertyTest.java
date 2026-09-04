package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 11: Expired code rejection

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OtpTokenEntity;
import com.foremen.dao.model.UserEntity;
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
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property 11: Expired code rejection.
 *
 * <p>For all persisted {@link OtpTokenEntity} rows whose {@code expiresAt} is equal to or earlier
 * than the current time (and whose {@code attempts} is below the max, so the earlier
 * attempts-exceeded branch does not fire), {@link OtpService#verifyCode(String, String)} SHALL
 * raise a {@link ForemenApiException} with HTTP 400 and message code {@code error.auth.otp.expired},
 * issue no tokens, and leave the entity {@code used} value unchanged.
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked collaborators (an
 * {@link OtpTokenDao} whose {@code findFirstByEmailIgnoreCaseOrderByCreatedDateDesc} returns the
 * expired token, a {@link UserDao}, an {@link OtpMailSender}, and a real {@link OtpCodeGenerator}),
 * following the existing OtpService property-test conventions. Because the expired branch precedes
 * the {@code used}/code-match branches and makes no state change, the mocks prove that on an
 * expired row the service rejects with the expired error, never consults the user store, never
 * persists the token (no attempts increment, no {@code used} mutation), and dispatches no email.
 *
 * <p>The submitted code is generated both matching and mismatching the stored code so the
 * expired outcome is exercised independently of whether the code would otherwise be correct, and
 * the stored token's {@code used} flag (seeded {@code false}) is asserted unchanged after the call.
 *
 * <p><b>Validates: Requirements 6.7</b>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 11: Expired code rejection")
class OtpVerifyExpiredPropertyTest {

    /** All collaborators of an {@link OtpService} under test. */
    private record Fixture(OtpService service,
                           OtpTokenDao otpTokenDao,
                           UserDao userDao,
                           OtpMailSender otpMailSender) {}

    private static Fixture fixture(OtpTokenEntity expiredToken) {
        OtpTokenDao otpTokenDao = Mockito.mock(OtpTokenDao.class);
        UserDao userDao = Mockito.mock(UserDao.class);
        OtpMailSender otpMailSender = Mockito.mock(OtpMailSender.class);
        OtpCodeGenerator codeGenerator = new OtpCodeGenerator();

        // The verify lookup returns the expired row for the email.
        when(otpTokenDao.findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString()))
                .thenReturn(Optional.of(expiredToken));
        // save returns its argument, mirroring the other OtpService property fixtures.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OtpService service = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);
        return new Fixture(service, otpTokenDao, userDao, otpMailSender);
    }

    /** An expired token: unused, attempts below the max, {@code expiresAt} at or before now. */
    private static OtpTokenEntity expiredToken(String code, int attempts, long secondsExpiredAgo) {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail("stored@example.com");
        token.setCode(code);
        token.setExpiresAt(Instant.now().minus(secondsExpiredAgo, ChronoUnit.SECONDS));
        token.setUsed(false);
        token.setAttempts(attempts);
        return token;
    }

    // Property 11: for every expired OtpTokenEntity (expiresAt <= now, attempts < 3), verifyCode
    // rejects with 400 error.auth.otp.expired, leaves used unchanged, and issues no tokens (no
    // user lookup, no persistence/increment, no email dispatch).
    // Validates: Requirements 6.7
    @Property(tries = 200)
    void expiredCodeRejectedWithUsedUnchangedAndNoTokens(
            @ForAll("emails") String email,
            @ForAll("codes") String storedCode,
            @ForAll("codes") String submittedCode,
            @ForAll("attemptsBelowMax") int attempts,
            @ForAll("secondsExpiredAgo") long secondsExpiredAgo) {

        OtpTokenEntity token = expiredToken(storedCode, attempts, secondsExpiredAgo);
        Fixture fx = fixture(token);

        assertThatThrownBy(() -> fx.service().verifyCode(email, submittedCode))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.otp.expired");
                });

        // (6.7) The entity used value is left unchanged.
        assertThat(token.isUsed())
                .as("an expired code rejection leaves used unchanged (false)")
                .isFalse();
        // No attempts increment for the expired branch.
        assertThat(token.getAttempts())
                .as("an expired code rejection makes no attempts increment")
                .isEqualTo(attempts);

        // The verify lookup was consulted.
        verify(fx.otpTokenDao()).findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString());
        // No token issuance: no user lookup, no persistence, no email dispatch.
        verify(fx.userDao(), never()).findByEmail(anyString());
        verify(fx.otpTokenDao(), never()).save(any(OtpTokenEntity.class));
        verify(fx.otpMailSender(), never()).send(any(UserEntity.class), anyString());
    }

    // --- Providers ---

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

    /** How long ago the token expired, in seconds — from just-expired (0) up to a day. */
    @Provide
    Arbitrary<Long> secondsExpiredAgo() {
        return Arbitraries.longs().between(0L, 86_400L);
    }
}
