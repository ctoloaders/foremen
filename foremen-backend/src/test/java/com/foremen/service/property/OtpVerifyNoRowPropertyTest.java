package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 8: Verify rejects a code that matches no active row

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
 * Property 8: Verify rejects a code that matches no active row.
 *
 * <p>For all (email, code) pairs for which no persisted OtpTokenEntity for that email exists,
 * {@link OtpService#verifyCode(String, String)} SHALL raise a {@link ForemenApiException} with
 * HTTP 400 and message code {@code error.auth.otp.invalid} and issue no tokens.
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked collaborators (an
 * {@link OtpTokenDao} whose {@code findFirstByEmailIgnoreCaseOrderByCreatedDateDesc} returns
 * {@link Optional#empty()} — the "no row for this email" case — a {@link UserDao}, an
 * {@link OtpMailSender}, and a real {@link OtpCodeGenerator}), following the existing OtpService
 * property-test conventions. The mocks let each property prove that when no row exists the service
 * rejects with the invalid-code error and never consults the user store, persists a token, or
 * dispatches an email (no tokens issued).
 *
 * <p><b>Validates: Requirements 6.4</b>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 8: Verify rejects a code that matches no active row")
class OtpVerifyNoRowPropertyTest {

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

        // No persisted row exists for any email — the verify lookup returns empty.
        when(otpTokenDao.findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(anyString()))
                .thenReturn(Optional.empty());
        // save returns its argument, mirroring the other OtpService property fixtures.
        when(otpTokenDao.save(any(OtpTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OtpService service = new OtpService(otpTokenDao, userDao, otpMailSender, codeGenerator);
        return new Fixture(service, otpTokenDao, userDao, otpMailSender);
    }

    // Property 8: for every (email, code) pair with no persisted row for the email, verifyCode
    // rejects with 400 error.auth.otp.invalid and issues no tokens (no user lookup, no persistence,
    // no email dispatch).
    // Validates: Requirements 6.4
    @Property(tries = 200)
    void noRowRejectsAsInvalidAndIssuesNoTokens(
            @ForAll("emails") String email,
            @ForAll("codes") String code) {
        Fixture fx = fixture();

        assertThatThrownBy(() -> fx.service().verifyCode(email, code))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.otp.invalid");
                });

        // The verify lookup was consulted and found nothing.
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

    /**
     * Submitted codes: 6-digit codes to mirror real OTP shape, plus arbitrary and blank-ish
     * strings, since with no persisted row the submitted code value is irrelevant to the outcome.
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
