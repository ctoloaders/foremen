package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 9: Wrong code increments attempts by exactly one

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
import org.mockito.ArgumentCaptor;
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
 * Property 9: Wrong code increments attempts by exactly one.
 *
 * <p>For all persisted {@link OtpTokenEntity} rows that are unused, unexpired, with {@code
 * attempts} less than 3, submitting a code that does not equal the stored code SHALL increment
 * {@code attempts} by exactly 1, raise a {@link ForemenApiException} with HTTP 400 and message
 * code {@code error.auth.otp.invalid}, and issue no tokens.
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked collaborators (an
 * {@link OtpTokenDao} whose {@code findFirstByEmailIgnoreCaseOrderByCreatedDateDesc} returns the
 * generated valid-but-mismatched token, a {@link UserDao}, an {@link OtpMailSender}, and a real
 * {@link OtpCodeGenerator}), following the existing OtpService property-test conventions
 * ({@link OtpVerifyNoRowPropertyTest}, {@link OtpVerifySuccessPropertyTest}). The submitted code
 * is always generated to differ from the stored code so the wrong-code branch is exercised.
 *
 * <p>The saved entity is captured to assert the {@code attempts} counter grew by exactly one
 * (from its starting value), and the collaborators prove no tokens are issued: on the wrong-code
 * branch the service never resolves the user or dispatches an email.
 *
 * <p><b>Validates: Requirements 6.5</b>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 9: Wrong code increments attempts by exactly one")
class OtpVerifyWrongCodePropertyTest {

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

        // save returns its argument so verifyCode can continue operating on the entity,
        // mirroring the other OtpService property fixtures.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OtpService service = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);
        return new Fixture(service, otpTokenDao, userDao, otpMailSender);
    }

    /** A fresh, valid token: unused, unexpired, attempts &lt; 3, holding {@code storedCode}. */
    private static OtpTokenEntity validToken(String email, String storedCode, int attempts) {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail(email);
        token.setCode(storedCode);
        token.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        token.setUsed(false);
        token.setAttempts(attempts);
        return token;
    }

    // Property 9: for every valid (unused, unexpired, attempts < 3) token, submitting a code that
    // does not equal the stored code increments attempts by exactly one, throws 400
    // error.auth.otp.invalid, and issues no tokens (no user lookup, no email dispatch).
    // Validates: Requirements 6.5
    @Property(tries = 200)
    void wrongCodeIncrementsAttemptsByExactlyOneAndRejects(
            @ForAll("emails") String email,
            @ForAll("codePairs") CodePair codePair,
            @ForAll("attemptsBelowMax") int startingAttempts) {
        Fixture fx = fixture();

        OtpTokenEntity token = validToken(email, codePair.stored(), startingAttempts);
        when(fx.otpTokenDao().findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString()))
                .thenReturn(Optional.of(token));

        // (6.5) A code that does not match the stored code is rejected as invalid with HTTP 400.
        assertThatThrownBy(() -> fx.service().verifyCode(email, codePair.submitted()))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.otp.invalid");
                });

        // (6.5) attempts is incremented by exactly one and the entity is persisted.
        ArgumentCaptor<OtpTokenEntity> savedCaptor = ArgumentCaptor.forClass(OtpTokenEntity.class);
        verify(fx.otpTokenDao()).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getAttempts())
                .as("wrong code increments attempts by exactly one")
                .isEqualTo(startingAttempts + 1);
        assertThat(token.getAttempts())
                .as("the persisted token entity reflects the incremented attempts")
                .isEqualTo(startingAttempts + 1);

        // (6.5) The token is not consumed by a wrong-code attempt.
        assertThat(token.isUsed())
                .as("a wrong-code attempt does not mark the token used")
                .isFalse();

        // (6.5) No tokens issued: the wrong-code branch never resolves the user or emails.
        verify(fx.userDao(), never()).findByEmail(anyString());
        verify(fx.otpMailSender(), never()).send(any(UserEntity.class), anyString());
    }

    // --- Providers ---

    /** A stored code paired with a distinct submitted code, so the wrong-code branch is hit. */
    private record CodePair(String stored, String submitted) {}

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
     * Pairs of (stored, submitted) codes where the two always differ. The stored code is a
     * realistic 6-digit OTP; the submitted code is any string constrained to be unequal, covering
     * differing digits, differing length, and blank-ish inputs.
     */
    @Provide
    Arbitrary<CodePair> codePairs() {
        Arbitrary<String> sixDigits = Arbitraries.strings()
                .withCharRange('0', '9')
                .ofLength(6);
        Arbitrary<String> submitted = Arbitraries.strings().ofMinLength(0).ofMaxLength(12);
        return Combinators.combine(sixDigits, submitted)
                .as(CodePair::new)
                .filter(pair -> !pair.stored().equals(pair.submitted()));
    }

    /** Attempt counts strictly below the max-attempts threshold (0, 1, 2). */
    @Provide
    Arbitrary<Integer> attemptsBelowMax() {
        return Arbitraries.integers().between(0, 2);
    }
}
