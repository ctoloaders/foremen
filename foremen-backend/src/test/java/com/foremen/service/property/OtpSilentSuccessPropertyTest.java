package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 4: Silent success for non-eligible emails

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
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property 4: Silent success for non-eligible emails.
 *
 * <p>For all emails that are not an Eligible_Email (unknown, non-CLIENT, or non-ACTIVE) requested
 * within the rate limit, {@code request} SHALL persist no OtpTokenEntity, dispatch no email, and
 * complete without raising an error, so its HTTP outcome is indistinguishable (both 200) from the
 * eligible case.
 *
 * <p>Each test wires a fresh {@link OtpService} over Mockito-mocked {@link OtpTokenDao},
 * {@link UserDao}, {@link OtpMailSender}, and a real {@link OtpCodeGenerator}, following the
 * existing OtpService property-test conventions. The rate-limit count defaults to {@code 0}
 * (Mockito default for the {@code long}-returning count method), so every generated email is
 * within the limit and the flow always reaches the eligibility branch under test.
 *
 * <p><b>Validates: Requirements 4.4</b>
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 4: Silent success for non-eligible emails")
class OtpSilentSuccessPropertyTest {

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

    /**
     * A non-eligible email — one that resolves to no user, to a non-CLIENT user, or to a
     * non-ACTIVE CLIENT user — never yields a persisted token or a dispatched email, and never
     * raises an error. The rate-limit count is left at the Mockito default {@code 0}, so the
     * request is always within the limit and reaches the eligibility branch.
     */
    @Property(tries = 100)
    void nonEligibleEmailPersistsNoTokenSendsNoEmailAndDoesNotThrow(
            @ForAll("emails") String email,
            @ForAll("nonEligibleLookup") Optional<NonEligibleUser> maybeUser) {
        Fixture fx = fixture();
        Optional<UserEntity> lookup = maybeUser.map(nu -> user(email, nu.status(), nu.roleCode()));
        when(fx.userDao().findByEmail(anyString())).thenReturn(lookup);

        assertThatCode(() -> fx.service().request(email))
                .as("silent success: a non-eligible email must not raise an error")
                .doesNotThrowAnyException();

        verify(fx.otpTokenDao(), never()).save(any(OtpTokenEntity.class));
        verify(fx.otpMailSender(), never()).send(any(UserEntity.class), anyString());
    }

    private static UserEntity user(String email, UserStatus status, String roleCode) {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("user");
        user.setEmail(email.toLowerCase());
        user.setStatus(status);
        RoleEntity role = new RoleEntity();
        role.setId(1L);
        role.setCode(roleCode);
        user.setRole(role);
        return user;
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

    /** A non-eligible user carries a (status, roleCode) pair that is not (ACTIVE, CLIENT). */
    private record NonEligibleUser(UserStatus status, String roleCode) {}

    /**
     * The three non-eligible partitions of a request lookup:
     * <ul>
     *   <li>no user at all ({@code Optional.empty()}) — unknown email;</li>
     *   <li>an ACTIVE user whose role code is not CLIENT — non-CLIENT;</li>
     *   <li>a CLIENT user whose status is not ACTIVE (INVITED / DEACTIVATED) — non-ACTIVE.</li>
     * </ul>
     * Every generated element fails the Eligible_Email predicate.
     */
    @Provide
    Arbitrary<Optional<NonEligibleUser>> nonEligibleLookup() {
        Arbitrary<NonEligibleUser> unknown = Arbitraries.of((NonEligibleUser) null);

        Arbitrary<String> nonClientRoleCode = Arbitraries.of("WORKER", "FOREMAN", "ADMIN", "MANAGER");
        Arbitrary<NonEligibleUser> activeNonClient = nonClientRoleCode
                .map(code -> new NonEligibleUser(UserStatus.ACTIVE, code));

        Arbitrary<UserStatus> nonActiveStatus =
                Arbitraries.of(UserStatus.INVITED, UserStatus.DEACTIVATED);
        Arbitrary<NonEligibleUser> nonActiveClient = nonActiveStatus
                .map(status -> new NonEligibleUser(status, CLIENT_ROLE_CODE));

        // Also cover a non-ACTIVE non-CLIENT user, which is doubly non-eligible.
        Arbitrary<NonEligibleUser> nonActiveNonClient = Combinators
                .combine(nonActiveStatus, nonClientRoleCode)
                .as(NonEligibleUser::new);

        return Arbitraries.oneOf(
                        unknown,
                        activeNonClient,
                        nonActiveClient,
                        nonActiveNonClient)
                .injectNull(0.0)
                .map(Optional::ofNullable);
    }
}
