package com.foremen.service.property;

// Feature: FOR-03-02-user-invitation, Property 9: Set-password activation and one-time-use invariant

import com.foremen.config.mail.InviteProperties;
import com.foremen.config.mail.MailInviteProperties;
import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.TokenResponse;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import com.foremen.service.RefreshTokenService;
import com.foremen.service.mail.InvitationMailSender;
import com.foremen.service.mail.MailSender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Property test for {@link AuthService#setPassword(String, String)} activation and the
 * one-time-use invariant (FOR-03-02, Requirements 5.4, 5.5, 5.9).
 *
 * <p><b>Property 9: Set-password activation and one-time-use invariant.</b> For all invite tokens
 * that exist, are unused, and are unexpired, whose owning user is INVITED, together with a password
 * of 8 to 72 characters, calling set-password sets the owner's {@code passwordHash} to a bcrypt
 * hash the supplied password verifies against, sets the owner's status to ACTIVE, sets the token
 * {@code used} to true, and returns a {@link TokenResponse} with a non-blank access and refresh
 * token; any subsequent set-password using the same token value is rejected with HTTP 400 and
 * message code {@code error.invite.token.used}.
 *
 * <p><b>Validates: Requirements 5.4, 5.5, 5.9</b>
 *
 * <p>Wiring: {@code AuthService.setPassword} delegates token validation to a <em>real</em>
 * {@link InviteService} (so {@code consume} runs against a mocked {@link InviteTokenDao}); the
 * encoder is a real {@link BCryptPasswordEncoder} at cost factor 12 (matching production) so
 * hash/verify assertions exercise genuine bcrypt work. {@link JwtTokenProvider} and
 * {@link RefreshTokenService} are stubbed to return fixed non-blank token values, keeping the
 * property focused on activation state rather than JWT internals. {@link UserDao} and
 * {@link PasswordResetTokenDao}/{@link MailSender} are mocked; {@code userDao.save} echoes its
 * argument so the service keeps operating on the same entity.
 */
@Tag("Feature: FOR-03-02-user-invitation, Property 9: Set-password activation and one-time-use")
class SetPasswordActivationPropertyTest {

    private static final String BASE_URL = "http://localhost:3000/auth/set-password";
    private static final int TTL_HOURS = 72;

    /** Cost factor 12, matching PasswordEncoderConfig (Requirement 5.4, bcrypt cost 12). */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    /**
     * All collaborators of the {@link AuthService} under test, so a property can drive DAO answers
     * and assert on the mutated entities.
     */
    private record Fixture(AuthService service,
                           InviteTokenDao inviteTokenDao,
                           UserDao userDao) {}

    /**
     * Builds an {@link AuthService} whose set-password path runs a real {@link InviteService} over
     * a mocked {@link InviteTokenDao}/{@link UserDao} and a mocked {@link InvitationMailSender}
     * (unused by the consume path). The JWT provider and refresh-token service are stubbed to
     * return fixed non-blank values.
     */
    private static Fixture fixture() {
        InviteTokenDao inviteTokenDao = Mockito.mock(InviteTokenDao.class);
        UserDao userDao = Mockito.mock(UserDao.class);
        InvitationMailSender invitationMailSender = Mockito.mock(InvitationMailSender.class);
        PasswordResetTokenDao passwordResetTokenDao = Mockito.mock(PasswordResetTokenDao.class);
        MailSender mailSender = Mockito.mock(MailSender.class);

        // save echoes its argument so the service keeps operating on the same, mutated entity.
        when(userDao.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inviteTokenDao.save(any(InviteTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        JwtTokenProvider jwtTokenProvider = Mockito.mock(JwtTokenProvider.class);
        when(jwtTokenProvider.generateAccessToken(any(), anyString(), anyString()))
                .thenReturn("access-token-value");

        RefreshTokenService refreshTokenService = Mockito.mock(RefreshTokenService.class);
        when(refreshTokenService.issue(any(UserEntity.class))).thenReturn("refresh-token-value");

        InviteProperties inviteProperties = new InviteProperties(TTL_HOURS);
        MailInviteProperties mailInviteProperties = new MailInviteProperties(BASE_URL);
        // The set-password/consume path never publishes an invitation event; a no-op publisher is fine.
        InviteService inviteService = new InviteService(
                inviteTokenDao, userDao, invitationMailSender,
                inviteProperties, mailInviteProperties, event -> { });

        JwtProperties props = new JwtProperties(30, 7, "unused-secret-for-mocked-provider-0123456789");
        AuthService service = new AuthService(
                userDao, ENCODER, jwtTokenProvider, refreshTokenService,
                props, passwordResetTokenDao, mailSender, inviteService, inviteTokenDao, null, null);

        return new Fixture(service, inviteTokenDao, userDao);
    }

    private static InviteTokenEntity validInvite(String token, long ttlSeconds) {
        RoleEntity role = new RoleEntity();
        role.setCode("MANAGER");
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("invited-user");
        user.setEmail("invited@example.com");
        user.setRole(role);
        user.setStatus(UserStatus.INVITED);
        user.setPasswordHash(null);

        InviteTokenEntity invite = new InviteTokenEntity();
        invite.setToken(token);
        invite.setUser(user);
        invite.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        invite.setUsed(false);
        return invite;
    }

    // Feature: FOR-03-02-user-invitation, Property 9: Set-password activation and one-time-use invariant
    // For all valid (existing, unused, unexpired, INVITED-owner) invite tokens and passwords of
    // length 8..72, setPassword hashes the password with bcrypt, activates the user, consumes the
    // token, and returns non-blank tokens; a second setPassword with the same token is rejected
    // with 400 error.invite.token.used.
    // Validates: Requirements 5.4, 5.5, 5.9
    @Property(tries = 100)
    void setPasswordActivatesAndConsumesTokenExactlyOnce(
            @ForAll("tokens") String token,
            @ForAll("passwords") String password,
            @ForAll @LongRange(min = 60L, max = 8760L * 3600L) long ttlSeconds) {

        Fixture f = fixture();
        InviteTokenEntity invite = validInvite(token, ttlSeconds);
        UserEntity owner = invite.getUser();
        // The same entity instance is returned on every lookup, so the used-flag mutation from the
        // first setPassword is visible to the second call's consume().
        when(f.inviteTokenDao.findByToken(token)).thenReturn(Optional.of(invite));

        TokenResponse response = f.service.setPassword(token, password);

        // 5.4: password hashed with bcrypt (verifies), user ACTIVE, token used.
        assertThat(owner.getPasswordHash()).as("password hash must be set on activation").isNotBlank();
        assertThat(ENCODER.matches(password, owner.getPasswordHash()))
                .as("stored bcrypt hash must verify against the supplied password").isTrue();
        assertThat(owner.getStatus()).as("owner must be ACTIVE after activation").isEqualTo(UserStatus.ACTIVE);
        assertThat(invite.isUsed()).as("consumed invite token must be marked used").isTrue();

        // 5.5: auto-login returns a non-blank access + refresh token pair.
        assertThat(response).isNotNull();
        assertThat(response.accessToken()).as("access token must be non-blank").isNotBlank();
        assertThat(response.refreshToken()).as("refresh token must be non-blank").isNotBlank();

        // 5.9: reusing the same (now used) token is rejected with 400 error.invite.token.used.
        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.setPassword(token, password), ForemenApiException.class);
        assertThat(ex).as("second set-password with the same token must be rejected").isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.invite.token.used");
    }

    // --- Providers ---

    /** Canonical UUID invite-token values (36 chars), as generated in production. */
    @Provide
    Arbitrary<String> tokens() {
        return Arbitraries.randomValue(random -> UUID.randomUUID().toString());
    }

    /** Passwords of length 8..72 inclusive, matching the accepted set-password policy. */
    @Provide
    Arbitrary<String> passwords() {
        return Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(8)
                .ofMaxLength(72);
    }
}
