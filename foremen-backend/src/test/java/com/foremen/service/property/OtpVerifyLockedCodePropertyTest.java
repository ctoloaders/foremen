package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 10: Locked code after max attempts

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
import net.jqwik.api.Combinators;
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
 * Property 10: Locked code after max attempts.
 *
 * <p>For all persisted {@link OtpTokenEntity} rows whose {@code attempts} is greater than or equal
 * to {@code MAX_ATTEMPTS} (3), {@link OtpService#verifyCode(String, String)} SHALL raise a
 * {@link ForemenApiException} with HTTP 400 and message code
 * {@code error.auth.otp.attempts.exceeded}, issue no tokens, and make no further increment to
 * {@code attempts} (no {@code save} side effect).
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked collaborators (an
 * {@link OtpTokenDao} whose {@code findFirstByEmailIgnoreCaseOrderByCreatedDateDesc} returns a
 * locked token, a {@link UserDao}, an {@link OtpMailSender}, and a real {@link OtpCodeGenerator}),
 * following the existing OtpService property-test conventions ({@code OtpVerifyNoRowPropertyTest}).
 * The locked branch precedes the expired/used/code-match branches, so the property holds regardless
 * of the row's expiry, used flag, stored code, or the submitted code.
 *
 * <p><b>Validates: Requirements 6.6</b>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 10: Locked code after max attempts")
class OtpVerifyLockedCodePropertyTest {

    /** All collaborators of an {@link OtpService} under test. */
    private record Fixture(OtpService service,
                           OtpTokenDao otpTokenDao,
                           UserDao userDao,
                           OtpMailSender otpMailSender) {}

    private static Fixture fixture(OtpTokenEntity lockedToken) {
        OtpTokenDao otpTokenDao = Mockito.mock(OtpTokenDao.class);
        UserDao userDao = Mockito.mock(UserDao.class);
        OtpMailSender otpMailSender = Mockito.mock(OtpMailSender.class);
        OtpCodeGenerator codeGenerator = new OtpCodeGenerator();

        // The newest token for the email is locked (attempts >= MAX_ATTEMPTS).
        when(otpTokenDao.findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString()))
                .thenReturn(Optional.of(lockedToken));
        // save returns its argument, mirroring the other OtpService property fixtures.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OtpService service = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);
        return new Fixture(service, otpTokenDao, userDao, otpMailSender);
    }

    // Property 10: for every locked token (attempts >= 3), verifyCode rejects with 400
    // error.auth.otp.attempts.exceeded, issues no tokens (no user lookup, no persistence, no email
    // dispatch), and does not increment attempts (no save side effect).
    // Validates: Requirements 6.6
    @Property(tries = 200)
    void lockedCodeRejectsWithoutIncrementAndIssuesNoTokens(
            @ForAll("lockedTokens") OtpTokenEntity lockedToken,
            @ForAll("codes") String submittedCode) {
        int attemptsBefore = lockedToken.getAttempts();
        boolean usedBefore = lockedToken.isUsed();
        Fixture fx = fixture(lockedToken);

        assertThatThrownBy(() -> fx.service().verifyCode(lockedToken.getEmail(), submittedCode))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.otp.attempts.exceeded");
                });

        // No further increment and no state change to the entity.
        assertThat(lockedToken.getAttempts()).isEqualTo(attemptsBefore);
        assertThat(lockedToken.isUsed()).isEqualTo(usedBefore);

        // The verify lookup was consulted; the locked branch fired first.
        verify(fx.otpTokenDao()).findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString());
        // No token issuance and no persistence: no user lookup, no save, no email dispatch.
        verify(fx.userDao(), never()).findByEmail(anyString());
        verify(fx.otpTokenDao(), never()).save(any(OtpTokenEntity.class));
        verify(fx.otpMailSender(), never()).send(any(UserEntity.class), anyString());
    }

    // --- Providers ---

    /**
     * Locked tokens: {@code attempts >= MAX_ATTEMPTS} (3). Expiry, used flag, stored code, and
     * email are varied freely because the locked branch precedes every other branch, so none of
     * them affect the outcome.
     */
    @Provide
    Arbitrary<OtpTokenEntity> lockedTokens() {
        Arbitrary<String> emails = emails();
        // MAX_ATTEMPTS is 3 (design.md); package-private on OtpService, so use the literal here.
        Arbitrary<Integer> attempts = Arbitraries.integers().between(3, 100);
        Arbitrary<String> storedCodes = Arbitraries.strings().withCharRange('0', '9').ofLength(6);
        Arbitrary<Boolean> usedFlags = Arbitraries.of(true, false);
        // Both future and past expiry to prove the locked branch precedes the expired branch.
        Arbitrary<Long> expiryOffsetMinutes = Arbitraries.longs().between(-60, 60);

        return Combinators.combine(emails, attempts, storedCodes, usedFlags, expiryOffsetMinutes)
                .as((email, att, storedCode, used, offset) -> {
                    OtpTokenEntity token = new OtpTokenEntity();
                    token.setEmail(email);
                    token.setCode(storedCode);
                    token.setExpiresAt(Instant.now().plus(offset, ChronoUnit.MINUTES));
                    token.setUsed(used);
                    token.setAttempts(att);
                    return token;
                });
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
     * Submitted codes: 6-digit codes to mirror real OTP shape, plus arbitrary and blank-ish
     * strings, since with a locked row the submitted code value is irrelevant to the outcome.
     */
    @Provide
    Arbitrary<String> codes() {
        Arbitrary<String> sixDigits = Arbitraries.strings()
                .withCharRange('0', '9')
                .ofLength(6);
        Arbitrary<String> arbitrary = Arbitraries.strings().ofMinLength(0).ofMaxLength(12);
        return Arbitraries.oneOf(sixDigits, arbitrary);
    }
}
