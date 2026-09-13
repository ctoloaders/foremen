package com.foremen.service.property;

// Feature: FOR-03-02-user-invitation, Property 11: Set-password rejects unknown tokens
// Feature: FOR-03-02-user-invitation, Property 12: Set-password rejects expired tokens
// Feature: FOR-03-02-user-invitation, Property 13: Set-password rejects already-used tokens
// Feature: FOR-03-02-user-invitation, Property 14: Set-password rejects invites for deactivated owners

import com.foremen.config.mail.InviteProperties;
import com.foremen.config.mail.MailInviteProperties;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.InviteService;
import com.foremen.service.mail.InvitationMailSender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property tests for {@link InviteService#consume(String)} rejection branches (FOR-03-02,
 * Requirements 5.6&ndash;5.8, 5.10).
 *
 * <p>Properties covered:
 * <ul>
 *   <li><b>Property 11: Set-password rejects unknown tokens</b> &mdash; a token matching no
 *       persisted entity yields {@code 400 error.invite.token.invalid} with no state change.
 *       <b>Validates: Requirements 5.6</b></li>
 *   <li><b>Property 12: Set-password rejects expired tokens</b> &mdash; a token whose
 *       {@code expiresAt} is at or before now yields {@code 400 error.invite.token.expired} with
 *       no state change. <b>Validates: Requirements 5.7</b></li>
 *   <li><b>Property 13: Set-password rejects already-used tokens</b> &mdash; a token whose
 *       {@code used} is true yields {@code 400 error.invite.token.used} with no state change.
 *       <b>Validates: Requirements 5.8</b></li>
 *   <li><b>Property 14: Set-password rejects invites for deactivated owners</b> &mdash; a valid,
 *       unused, unexpired token whose owning user is {@code DEACTIVATED} yields
 *       {@code 409 error.invite.user.already.active} with the token left unchanged.
 *       <b>Validates: Requirements 5.10</b></li>
 * </ul>
 *
 * <p>The {@link InviteTokenDao} is mocked to return generated token states; "no state change on
 * rejection" is asserted by verifying the service never calls {@code save} on the DAO for any
 * rejection branch.
 */
class InviteConsumePropertyTest {

    private static final String BASE_URL = "http://localhost:3000/auth/set-password";
    private static final int TTL_HOURS = 72;

    // ---- Property 11: Set-password rejects unknown tokens (Requirement 5.6) ----

    @Property(tries = 100)
    void unknownTokenIsRejectedWithInvalidAndNoStateChange(@ForAll("tokenValues") String token) {
        Fixture f = new Fixture();
        when(f.inviteTokenDao.findByToken(token)).thenReturn(Optional.empty());

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.consume(token), ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.invite.token.invalid");
        // No state change: nothing persisted.
        verify(f.inviteTokenDao, never()).save(any());
    }

    // ---- Property 13: Set-password rejects already-used tokens (Requirement 5.8) ----
    // Ordered before expiry to mirror the service evaluation order (used precedes expired).

    @Property(tries = 100)
    void usedTokenIsRejectedWithUsedAndNoStateChange(
            @ForAll("tokenValues") String token,
            @ForAll("ownerStatuses") UserStatus ownerStatus,
            @ForAll boolean expired) {

        Fixture f = new Fixture();
        // used == true takes precedence regardless of expiry or owner status.
        Instant expiresAt = expired
                ? Instant.now().minus(1, ChronoUnit.HOURS)
                : Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS);
        InviteTokenEntity invite = invite(token, expiresAt, true, ownerStatus);
        when(f.inviteTokenDao.findByToken(token)).thenReturn(Optional.of(invite));

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.consume(token), ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.invite.token.used");
        // No state change: token still used == true, nothing persisted.
        assertThat(invite.isUsed()).isTrue();
        verify(f.inviteTokenDao, never()).save(any());
    }

    // ---- Property 12: Set-password rejects expired tokens (Requirement 5.7) ----

    @Property(tries = 100)
    void expiredUnusedTokenIsRejectedWithExpiredAndNoStateChange(
            @ForAll("tokenValues") String token,
            @ForAll("ownerStatuses") UserStatus ownerStatus,
            @ForAll("expiredOffsetsSeconds") long offsetSeconds) {

        Fixture f = new Fixture();
        // expiresAt <= now (offsetSeconds >= 0 subtracted from now); token unused so expiry is reached.
        Instant expiresAt = Instant.now().minusSeconds(offsetSeconds);
        InviteTokenEntity invite = invite(token, expiresAt, false, ownerStatus);
        when(f.inviteTokenDao.findByToken(token)).thenReturn(Optional.of(invite));

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.consume(token), ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.invite.token.expired");
        // No state change: token still unused, nothing persisted.
        assertThat(invite.isUsed()).isFalse();
        verify(f.inviteTokenDao, never()).save(any());
    }

    // ---- Property 14: Set-password rejects invites for deactivated owners (Requirement 5.10) ----

    @Property(tries = 100)
    void deactivatedOwnerIsRejectedWithConflictAndTokenUnchanged(
            @ForAll("tokenValues") String token) {

        Fixture f = new Fixture();
        // Valid, unused, unexpired token whose owner is DEACTIVATED.
        Instant expiresAt = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS);
        InviteTokenEntity invite = invite(token, expiresAt, false, UserStatus.DEACTIVATED);
        when(f.inviteTokenDao.findByToken(token)).thenReturn(Optional.of(invite));

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.consume(token), ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessageCode()).isEqualTo("error.invite.user.already.active");
        // Token left unchanged (used stays false), nothing persisted (Requirement 5.10).
        assertThat(invite.isUsed()).isFalse();
        verify(f.inviteTokenDao, never()).save(any());
    }

    // ---- Sanity: a valid INVITED-owner token is returned unchanged (contrast to rejection) ----

    @Property(tries = 100)
    void validInvitedTokenIsReturnedUnchanged(@ForAll("tokenValues") String token) {
        Fixture f = new Fixture();
        Instant expiresAt = Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS);
        InviteTokenEntity invite = invite(token, expiresAt, false, UserStatus.INVITED);
        when(f.inviteTokenDao.findByToken(token)).thenReturn(Optional.of(invite));

        InviteTokenEntity result = f.service.consume(token);

        assertThat(result).isSameAs(invite);
        assertThat(result.isUsed()).isFalse();
        verify(f.inviteTokenDao, never()).save(any());
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a fresh {@link InviteService} with mocked collaborators. Only {@link InviteTokenDao}
     * is stubbed per test; {@link UserDao} and {@link InvitationMailSender} are unused by
     * {@code consume} and kept as bare mocks.
     */
    private static final class Fixture {
        final InviteTokenDao inviteTokenDao = Mockito.mock(InviteTokenDao.class);
        final UserDao userDao = Mockito.mock(UserDao.class);
        final InvitationMailSender invitationMailSender = Mockito.mock(InvitationMailSender.class);
        final InviteService service;

        Fixture() {
            InviteProperties inviteProperties = new InviteProperties(TTL_HOURS);
            MailInviteProperties mailInviteProperties = new MailInviteProperties(BASE_URL);
            // consume() never publishes an invitation event, so a no-op publisher is sufficient.
            service = new InviteService(
                    inviteTokenDao, userDao, invitationMailSender,
                    inviteProperties, mailInviteProperties, event -> { });
        }
    }

    private static InviteTokenEntity invite(
            String token, Instant expiresAt, boolean used, UserStatus ownerStatus) {
        RoleEntity role = new RoleEntity();
        role.setCode("MANAGER");
        UserEntity user = new UserEntity();
        user.setEmail("owner@example.com");
        user.setRole(role);
        user.setStatus(ownerStatus);

        InviteTokenEntity invite = new InviteTokenEntity();
        invite.setToken(token);
        invite.setUser(user);
        invite.setExpiresAt(expiresAt);
        invite.setUsed(used);
        return invite;
    }

    /** Arbitrary token values: canonical UUID strings plus arbitrary non-blank strings. */
    @Provide
    Arbitrary<String> tokenValues() {
        Arbitrary<String> uuids = Arbitraries.randomValue(random -> UUID.randomUUID().toString());
        Arbitrary<String> arbitrary =
                Arbitraries.strings().ofMinLength(1).ofMaxLength(60).filter(s -> !s.isBlank());
        return Arbitraries.oneOf(uuids, arbitrary);
    }

    /** Owner statuses that are NOT DEACTIVATED, used where owner status must not trigger 409. */
    @Provide
    Arbitrary<UserStatus> ownerStatuses() {
        return Arbitraries.of(UserStatus.INVITED, UserStatus.ACTIVE);
    }

    /** Non-negative second offsets subtracted from now, so expiresAt <= now (expired or exactly now). */
    @Provide
    Arbitrary<Long> expiredOffsetsSeconds() {
        return Arbitraries.longs().between(0L, 100_000L);
    }
}
