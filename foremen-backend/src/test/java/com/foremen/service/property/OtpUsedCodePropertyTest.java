package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 12: Used code is single-use

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Property 12: Used code is single-use.
 *
 * <p>For all persisted {@link OtpTokenEntity} rows whose {@code used} value is true (with
 * {@code attempts} below the max-attempts threshold and an {@code expiresAt} in the future, so the
 * earlier attempts-exceeded and expired branches do not fire),
 * {@link OtpService#verifyCode(String, String)} SHALL raise a {@link ForemenApiException} with
 * HTTP 400 and message code {@code error.auth.otp.invalid} and issue no tokens. Consequently a
 * code that verified successfully once SHALL be rejected on every subsequent verification.
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked collaborators (an
 * {@link OtpTokenDao} whose {@code findFirstByEmailIgnoreCaseOrderByCreatedDateDesc} returns the
 * already-used token, a {@link UserDao}, an {@link OtpMailSender}, and a real
 * {@link OtpCodeGenerator}), following the existing OtpService property-test conventions. Because
 * the used-code branch precedes the code-match and eligibility branches in the verify state
 * machine, the test proves rejection regardless of whether the submitted code matches the stored
 * one, and confirms no user lookup, no persistence, and no email dispatch occur (no tokens issued).
 *
 * <p><b>Validates: Requirements 6.8, 6.10</b>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 12: Used code is single-use")
class OtpUsedCodePropertyTest {

    private static final String CLIENT_ROLE_CODE = "CLIENT";

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

        // save returns its argument, mirroring the other OtpService property fixtures.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OtpService service = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);
        return new Fixture(service, otpTokenDao, userDao, otpMailSender);
    }

    /** An already-used token: used=true, unexpired, attempts strictly below max, with the code. */
    private static OtpTokenEntity usedToken(String email, String code, int attempts) {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail(email);
        token.setCode(code);
        token.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        token.setUsed(true);
        token.setAttempts(attempts);
        return token;
    }

    /** An ACTIVE CLIENT user — the only shape that is an Eligible_Email. */
    private static UserEntity eligibleClient(String email) {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("client");
        user.setEmail(email.toLowerCase());
        user.setStatus(UserStatus.ACTIVE);
        RoleEntity role = new RoleEntity();
        role.setId(1L);
        role.setCode(CLIENT_ROLE_CODE);
        user.setRole(role);
        return user;
    }

    // Property 12: for every already-used token (unexpired, attempts < 3), verifyCode rejects with
    // 400 error.auth.otp.invalid and issues no tokens — regardless of whether the submitted code
    // matches the stored one. No user lookup, no persistence, no email dispatch.
    // Validates: Requirements 6.8, 6.10
    @Property(tries = 200)
    void usedTokenRejectsAsInvalidAndIssuesNoTokens(
            @ForAll("emails") String email,
            @ForAll("codes") String storedCode,
            @ForAll("codes") String submittedCode,
            @ForAll("attemptsBelowMax") int attempts) {
        Fixture fx = fixture();

        OtpTokenEntity token = usedToken(email, storedCode, attempts);
        when(fx.otpTokenDao().findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString()))
                .thenReturn(Optional.of(token));

        assertThatThrownBy(() -> fx.service().verifyCode(email, submittedCode))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.otp.invalid");
                });

        // The used flag is unchanged and no token issuance occurs.
        assertThat(token.isUsed()).as("an already-used token remains used").isTrue();
        verify(fx.userDao(), never()).findByEmail(anyString());
        verify(fx.otpTokenDao(), never()).save(any(OtpTokenEntity.class));
        verify(fx.otpMailSender(), never()).send(any(UserEntity.class), anyString());
    }

    // Property 12 (one-time-use): a code that verifies successfully once is rejected on a second
    // verification with the same email and code. The first call consumes the code (used=true); a
    // repeated verify against the now-used row is rejected with 400 error.auth.otp.invalid and
    // issues no further token.
    // Validates: Requirements 6.8, 6.10
    @Property(tries = 200)
    void codeCannotBeVerifiedTwice(
            @ForAll("emails") String email,
            @ForAll("codes") String code) {
        Fixture fx = fixture();

        UserEntity client = eligibleClient(email);
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail(email);
        token.setCode(code);
        token.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        token.setUsed(false);
        token.setAttempts(0);

        // The verify lookup always returns the same row; the eligibility confirmation resolves the
        // ACTIVE CLIENT user for the first (successful) call.
        when(fx.otpTokenDao().findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString()))
                .thenReturn(Optional.of(token));
        when(fx.userDao().findByEmail(anyString())).thenReturn(Optional.of(client));

        // First verification succeeds and consumes the code.
        UserEntity authenticated = fx.service().verifyCode(email, code);
        assertThat(authenticated).isSameAs(client);
        assertThat(token.isUsed()).as("first successful verify marks the code used").isTrue();

        // Second verification of the same code is rejected as invalid.
        assertThatThrownBy(() -> fx.service().verifyCode(email, code))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.otp.invalid");
                });
        assertThat(token.isUsed()).as("the code stays used after the rejected re-verify").isTrue();
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
}
