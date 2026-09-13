package com.foremen.service.property;

// Feature: FOR-03-02-user-invitation, Property 15: Resend for INVITED users rotates the token
// Feature: FOR-03-02-user-invitation, Property 16: Resend rejects unknown users
// Feature: FOR-03-02-user-invitation, Property 17: Resend rejects non-INVITED users

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
import com.foremen.service.mail.InvitationEmailDispatcher;
import com.foremen.service.mail.InvitationEmailEvent;
import com.foremen.service.mail.InvitationMailSender;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property tests for {@link InviteService#resend(Long)} (FOR-03-02, Requirement 6).
 *
 * <p>Properties covered:
 * <ul>
 *   <li><b>Property 15: Resend for INVITED users rotates the token</b> &mdash; for an INVITED user,
 *       every prior unused token is marked {@code used = true} and exactly one new, distinct token
 *       is persisted ({@code used = false}, fresh expiry) with exactly one invitation email sent.
 *       <b>Validates: Requirements 6.6</b></li>
 *   <li><b>Property 16: Resend rejects unknown users</b> &mdash; an unknown {@code userId} yields a
 *       404 {@code error.invite.user.not.found} with no token save and no email.
 *       <b>Validates: Requirements 6.7</b></li>
 *   <li><b>Property 17: Resend rejects non-INVITED users</b> &mdash; an ACTIVE or DEACTIVATED user
 *       yields a 409 {@code error.invite.user.already.active} with no state change and no email.
 *       <b>Validates: Requirements 6.8, 6.9</b></li>
 * </ul>
 *
 * <p>Collaborators are mocked with Mockito: {@link UserDao#findById(Object)} returns the generated
 * user (or empty for the unknown case); {@link InviteTokenDao} echoes saved entities back and
 * serves the prior unused-token set via {@code findByUserIdAndUsedFalse}; the
 * {@link InvitationMailSender} records dispatches.
 */
class InviteResendPropertyTest {

    private static final String BASE_URL = "http://localhost:3000/auth/set-password";
    private static final int TTL_HOURS = 72;

    // ---- Property 15: Resend for INVITED users rotates the token (Requirement 6.6) ----

    @Property(tries = 100)
    void resendForInvitedUserRotatesTheToken(
            @ForAll("emails") String email,
            @ForAll("anyRoleCodes") String roleCode,
            @ForAll @LongRange(min = 1, max = 1_000_000) long userId,
            @ForAll @IntRange(min = 0, max = 5) int priorTokenCount) {

        Fixture f = new Fixture(TTL_HOURS);
        UserEntity user = user(userId, "Invited User", email, roleCode, UserStatus.INVITED);
        when(f.userDao.findById(userId)).thenReturn(Optional.of(user));

        List<InviteTokenEntity> prior = priorUnusedTokens(user, priorTokenCount);
        when(f.inviteTokenDao.findByUserIdAndUsedFalse(userId)).thenReturn(prior);

        Instant before = Instant.now();
        f.service.resend(userId);
        Instant after = Instant.now();

        // Every prior unused token is marked used = true (rotation invalidates the old tokens).
        for (InviteTokenEntity oldToken : prior) {
            assertThat(oldToken.isUsed()).isTrue();
        }

        // save is called once per prior token (to persist used=true) plus once for the new token.
        ArgumentCaptor<InviteTokenEntity> captor = ArgumentCaptor.forClass(InviteTokenEntity.class);
        verify(f.inviteTokenDao, times(priorTokenCount + 1)).save(captor.capture());

        // The last save is the freshly generated token: distinct value, unused, fresh expiry.
        List<InviteTokenEntity> saved = captor.getAllValues();
        InviteTokenEntity fresh = saved.get(saved.size() - 1);
        assertThat(fresh.getToken()).isNotNull().hasSize(36);
        assertThat(fresh.getToken()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(fresh.isUsed()).isFalse();
        assertThat(fresh.getUser()).isSameAs(user);
        for (InviteTokenEntity oldToken : prior) {
            assertThat(fresh.getToken()).isNotEqualTo(oldToken.getToken());
        }

        // Fresh expiry equals now + configured TTL (within ±5 s).
        Instant lowerBound = before.plus(TTL_HOURS, ChronoUnit.HOURS).minusSeconds(5);
        Instant upperBound = after.plus(TTL_HOURS, ChronoUnit.HOURS).plusSeconds(5);
        assertThat(fresh.getExpiresAt()).isAfterOrEqualTo(lowerBound).isBeforeOrEqualTo(upperBound);

        // Exactly one invitation email is sent (role-dependent), and no other variant.
        if ("CLIENT".equalsIgnoreCase(roleCode)) {
            verify(f.invitationMailSender, times(1)).sendClientPortalInvitation(any());
            verify(f.invitationMailSender, never()).sendSetPasswordInvitation(any(), any());
        } else {
            verify(f.invitationMailSender, times(1)).sendSetPasswordInvitation(any(), any());
            verify(f.invitationMailSender, never()).sendClientPortalInvitation(any());
        }
    }

    // ---- Property 16: Resend rejects unknown users (Requirement 6.7) ----

    @Property(tries = 100)
    void resendRejectsUnknownUsers(@ForAll @LongRange(min = 1, max = 1_000_000) long userId) {
        Fixture f = new Fixture(TTL_HOURS);
        when(f.userDao.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> f.service.resend(userId))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageCode()).isEqualTo("error.invite.user.not.found");
                });

        // No state change and no email.
        verify(f.inviteTokenDao, never()).save(any());
        verify(f.invitationMailSender, never()).sendSetPasswordInvitation(any(), any());
        verify(f.invitationMailSender, never()).sendClientPortalInvitation(any());
    }

    // ---- Property 17: Resend rejects non-INVITED users (Requirements 6.8, 6.9) ----

    @Property(tries = 100)
    void resendRejectsNonInvitedUsers(
            @ForAll("emails") String email,
            @ForAll("anyRoleCodes") String roleCode,
            @ForAll @LongRange(min = 1, max = 1_000_000) long userId,
            @ForAll("nonInvitedStatuses") UserStatus status) {

        Fixture f = new Fixture(TTL_HOURS);
        UserEntity user = user(userId, "Non-Invited User", email, roleCode, status);
        when(f.userDao.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> f.service.resend(userId))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessageCode()).isEqualTo("error.invite.user.already.active");
                });

        // No token lookup/rotation, no save, no email; the user status is untouched.
        verify(f.inviteTokenDao, never()).findByUserIdAndUsedFalse(anyLong());
        verify(f.inviteTokenDao, never()).save(any());
        verify(f.invitationMailSender, never()).sendSetPasswordInvitation(any(), any());
        verify(f.invitationMailSender, never()).sendClientPortalInvitation(any());
        assertThat(user.getStatus()).isEqualTo(status);
    }

    // ---- Fixture and helpers ----

    private static final class Fixture {
        final InviteTokenDao inviteTokenDao = Mockito.mock(InviteTokenDao.class);
        final UserDao userDao = Mockito.mock(UserDao.class);
        final InvitationMailSender invitationMailSender = Mockito.mock(InvitationMailSender.class);
        final InviteService service;

        Fixture(int ttlHours) {
            when(inviteTokenDao.save(any(InviteTokenEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            InviteProperties inviteProperties = new InviteProperties(ttlHours);
            MailInviteProperties mailInviteProperties = new MailInviteProperties(BASE_URL);
            // Wire the publisher to synchronously drive a dispatcher over the mocked mail sender so
            // the "exactly one email on resend" assertions remain meaningful without async timing.
            InvitationEmailDispatcher dispatcher =
                    new InvitationEmailDispatcher(invitationMailSender);
            ApplicationEventPublisher publisher =
                    event -> dispatcher.onInvitationEmail((InvitationEmailEvent) event);
            service = new InviteService(
                    inviteTokenDao, userDao, invitationMailSender,
                    inviteProperties, mailInviteProperties, publisher);
        }
    }

    private static UserEntity user(long id, String name, String email, String roleCode, UserStatus status) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    /** Builds {@code count} distinct prior unused tokens owned by the user. */
    private static List<InviteTokenEntity> priorUnusedTokens(UserEntity user, int count) {
        List<InviteTokenEntity> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            InviteTokenEntity token = new InviteTokenEntity();
            token.setToken("prior-token-" + i + "-" + java.util.UUID.randomUUID());
            token.setUser(user);
            token.setExpiresAt(Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS));
            token.setUsed(false);
            tokens.add(token);
        }
        return tokens;
    }

    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> domain = Arbitraries.of("example.com", "foremen.pl", "mail.test", "corp.io");
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d);
    }

    /** Full partition: the CLIENT code plus employee (non-client, non-admin) codes. */
    @Provide
    Arbitrary<String> anyRoleCodes() {
        return Arbitraries.of(
                "CLIENT", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CUSTOM_ROLE", "SUPERVISOR");
    }

    /** Non-INVITED statuses that must be rejected on resend (Requirements 6.8, 6.9). */
    @Provide
    Arbitrary<UserStatus> nonInvitedStatuses() {
        return Arbitraries.of(UserStatus.ACTIVE, UserStatus.DEACTIVATED);
    }
}
