package com.foremen.service.property;

// Feature: FOR-03-02-user-invitation, Property 1: Invite issuance invariant
// Feature: FOR-03-02-user-invitation, Property 2: Invite-token expiry equals issuance plus configured TTL
// Feature: FOR-03-02-user-invitation, Property 4: Generated invite tokens are distinct
// Feature: FOR-03-02-user-invitation, Property 5: Employee invitations send exactly one set-password email
// Feature: FOR-03-02-user-invitation, Property 6: Client invitations send exactly one client-portal email

import com.foremen.config.mail.InviteProperties;
import com.foremen.config.mail.MailInviteProperties;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property tests for {@link InviteService} issuance and role-dependent email dispatch.
 *
 * <p>Properties covered:
 * <ul>
 *   <li><b>Property 1: Invite issuance invariant</b> &mdash; issuing an invite persists exactly one
 *       token (36-char UUID string, {@code used = false}, associated with the user).
 *       <b>Validates: Requirements 3.1, 3.3, 3.8, 1.1</b></li>
 *   <li><b>Property 2: Invite-token expiry equals issuance plus configured TTL</b> &mdash; the
 *       persisted token's {@code expiresAt} equals the issuance instant plus the configured TTL
 *       (within ±5 s). <b>Validates: Requirements 3.2, 6.6</b></li>
 *   <li><b>Property 4: Generated invite tokens are distinct</b> &mdash; repeated issuances produce
 *       distinct token values. <b>Validates: Requirements 3.5</b></li>
 *   <li><b>Property 5: Employee invitations send exactly one set-password email</b> &mdash; an
 *       employee-role user receives exactly one set-password email and no client-portal email.
 *       <b>Validates: Requirements 4.1</b></li>
 *   <li><b>Property 6: Client invitations send exactly one client-portal email</b> &mdash; a
 *       CLIENT-role user receives exactly one client-portal email and no set-password email.
 *       <b>Validates: Requirements 4.2, 4.4</b></li>
 * </ul>
 *
 * <p>Collaborators are mocked with Mockito: the {@link InviteTokenDao} captures saved entities and
 * echoes them back from {@code save}; the {@link InvitationMailSender} records dispatches. Role
 * codes are generated partitioned into a CLIENT set and an employee (non-client, non-admin) set.
 */
class InviteIssuancePropertyTest {

    private static final String BASE_URL = "http://localhost:3000/auth/set-password";
    private static final int TTL_HOURS = 72;

    // ---- Property 1: Invite issuance invariant (Requirements 3.1, 3.3, 3.8, 1.1) ----

    @Property(tries = 100)
    void issuePersistsExactlyOneValidTokenForTheUser(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll("employeeRoleCodes") String roleCode) {

        Fixture f = new Fixture(TTL_HOURS);
        UserEntity user = user(name, email, roleCode, UserStatus.INVITED);

        f.service.issueInvite(user);

        // Exactly one token persisted.
        ArgumentCaptor<InviteTokenEntity> captor = ArgumentCaptor.forClass(InviteTokenEntity.class);
        verify(f.inviteTokenDao, times(1)).save(captor.capture());

        InviteTokenEntity saved = captor.getValue();
        // 36-char canonical UUID string (Requirement 3.1 / 1.1).
        assertThat(saved.getToken()).isNotNull().hasSize(36);
        assertThat(saved.getToken()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        // used == false and associated with the user (Requirement 3.3).
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.getUser()).isSameAs(user);
    }

    // ---- Property 2: Expiry equals issuance plus configured TTL (Requirements 3.2, 6.6) ----

    @Property(tries = 100)
    void expiryEqualsIssuanceInstantPlusConfiguredTtl(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll("anyRoleCodes") String roleCode) {

        Fixture f = new Fixture(TTL_HOURS);
        UserEntity user = user(name, email, roleCode, UserStatus.INVITED);

        Instant before = Instant.now();
        f.service.issueInvite(user);
        Instant after = Instant.now();

        ArgumentCaptor<InviteTokenEntity> captor = ArgumentCaptor.forClass(InviteTokenEntity.class);
        verify(f.inviteTokenDao).save(captor.capture());
        Instant expiresAt = captor.getValue().getExpiresAt();

        Instant lowerBound = before.plus(TTL_HOURS, ChronoUnit.HOURS).minusSeconds(5);
        Instant upperBound = after.plus(TTL_HOURS, ChronoUnit.HOURS).plusSeconds(5);
        assertThat(expiresAt).isAfterOrEqualTo(lowerBound).isBeforeOrEqualTo(upperBound);
    }

    // ---- Property 4: Generated invite tokens are distinct (Requirement 3.5) ----

    @Property(tries = 100)
    void repeatedIssuancesProduceDistinctTokens(
            @ForAll("emails") String email,
            @ForAll("anyRoleCodes") String roleCode) {

        Fixture f = new Fixture(TTL_HOURS);
        UserEntity user = user("Batch User", email, roleCode, UserStatus.INVITED);

        int iterations = 50;
        for (int i = 0; i < iterations; i++) {
            f.service.issueInvite(user);
        }

        ArgumentCaptor<InviteTokenEntity> captor = ArgumentCaptor.forClass(InviteTokenEntity.class);
        verify(f.inviteTokenDao, times(iterations)).save(captor.capture());

        Set<String> distinct = new HashSet<>();
        for (InviteTokenEntity token : captor.getAllValues()) {
            distinct.add(token.getToken());
        }
        assertThat(distinct).hasSize(iterations);
    }

    // ---- Property 5: Employee invitations send exactly one set-password email (Requirement 4.1) ----

    @Property(tries = 100)
    void employeeUserGetsExactlyOneSetPasswordEmail(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll("employeeRoleCodes") String roleCode) {

        Fixture f = new Fixture(TTL_HOURS);
        UserEntity user = user(name, email, roleCode, UserStatus.INVITED);

        f.service.issueInvite(user);

        // Exactly one set-password email carrying the invite link built from the persisted token.
        ArgumentCaptor<InviteTokenEntity> tokenCaptor = ArgumentCaptor.forClass(InviteTokenEntity.class);
        verify(f.inviteTokenDao).save(tokenCaptor.capture());
        String expectedLink = BASE_URL + "?token=" + tokenCaptor.getValue().getToken();

        verify(f.invitationMailSender, times(1)).sendSetPasswordInvitation(eq(user), eq(expectedLink));
        verify(f.invitationMailSender, never()).sendClientPortalInvitation(any());
    }

    // ---- Property 6: Client invitations send exactly one client-portal email (Requirements 4.2, 4.4) ----

    @Property(tries = 100)
    void clientUserGetsExactlyOneClientPortalEmail(
            @ForAll("names") String name,
            @ForAll("emails") String email) {

        Fixture f = new Fixture(TTL_HOURS);
        UserEntity user = user(name, email, "CLIENT", UserStatus.INVITED);

        f.service.issueInvite(user);

        verify(f.invitationMailSender, times(1)).sendClientPortalInvitation(eq(user));
        // No set-password email (and therefore no link) for a client (Requirement 4.4).
        verify(f.invitationMailSender, never()).sendSetPasswordInvitation(any(), any());
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a fresh {@link InviteService} with its mocked collaborators. The {@link InviteTokenDao}
     * echoes back whatever entity is passed to {@code save} so the service can read the persisted
     * token when building the invite link.
     */
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
            // Wire the event publisher so a published InvitationEmailEvent synchronously drives an
            // InvitationEmailDispatcher built over the mocked mail sender. This preserves the
            // "exactly one email + link" assertions (Properties 5 & 6) without async timing.
            InvitationEmailDispatcher dispatcher =
                    new InvitationEmailDispatcher(invitationMailSender);
            ApplicationEventPublisher publisher =
                    event -> dispatcher.onInvitationEmail((InvitationEmailEvent) event);
            service = new InviteService(
                    inviteTokenDao, userDao, invitationMailSender,
                    inviteProperties, mailInviteProperties, publisher);
        }
    }

    private static UserEntity user(String name, String email, String roleCode, UserStatus status) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> domain = Arbitraries.of("example.com", "foremen.pl", "mail.test", "corp.io");
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d);
    }



    /** Employee role codes: any non-CLIENT, non-ADMIN code (Employee_Role). */
    @Provide
    Arbitrary<String> employeeRoleCodes() {
        return Arbitraries.of("MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CUSTOM_ROLE", "SUPERVISOR");
    }

    /** Full partition: the CLIENT code plus the employee codes. */
    @Provide
    Arbitrary<String> anyRoleCodes() {
        return Arbitraries.of(
                "CLIENT", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CUSTOM_ROLE", "SUPERVISOR");
    }
}
