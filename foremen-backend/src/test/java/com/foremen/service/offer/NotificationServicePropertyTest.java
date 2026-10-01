package com.foremen.service.offer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.http.HttpStatus;

import com.foremen.dao.NotificationDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.offer.OfferNegotiationNotificationEvent.Trigger;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.LongRange;

/**
 * Property-based tests for the recipient-scoped {@link NotificationService} ownership guard, the
 * bell unread-badge count, and the best-effort {@link OfferNotificationEmitter} emission
 * (FOR-05-07, design §Property 18 / §Property 19 / §Property 20).
 *
 * <p><b>Chosen test level.</b> None of the three properties are pure functions:
 * {@code NotificationService} resolves the notification / recipient from a DAO and enforces
 * ownership by the acting user id, and {@code OfferNotificationEmitter} resolves the recipient and
 * delegates to {@code NotificationService.create}. All three are therefore exercised at the service
 * level with <b>mocked collaborators</b> — {@link NotificationDao} / {@link UserDao} /
 * {@link EntityManager} for the service, and a (deliberately throwing) {@link NotificationService}
 * plus {@link ProjectMemberDao} for the emitter — running entirely in memory over 100+ iterations
 * with no Spring context and no Testcontainers. This mirrors the
 * {@code NegotiationServiceGuardsPropertyTest} / {@code OfferDiscountServiceTest} conventions.
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 18: Notification access is limited to the acting
 * user's own notifications</b> — <b>Validates: Requirements 10.9, 10.19, 13.8, 13.9, 13.13</b>
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 19: The bell unread badge count equals the
 * user's unread notifications</b> — <b>Validates: Requirements 10.10</b>
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 20: A failed notification emission never blocks
 * the triggering transition</b> — <b>Validates: Requirements 10.11, 14.5</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 18: Notification access is limited to the acting user's own notifications")
@Tag("Feature: FOR-05-07-offer-approval, Property 19: The bell unread badge count equals the user's unread notifications")
@Tag("Feature: FOR-05-07-offer-approval, Property 20: A failed notification emission never blocks the triggering transition")
class NotificationServicePropertyTest {

    private static final long NOTIFICATION_ID = 900L;
    private static final String FORBIDDEN_MESSAGE = "error.notification.forbidden";
    private static final String MANAGER_ROLE = "MANAGER";

    // ------------------------------------------------------------------------------------------
    // Property 18: Notification access is limited to the acting user's own notifications.
    // For any notification owned by `ownerId` and any acting user `actingUserId`, toggleRead/delete
    // is permitted iff ownerId == actingUserId. When they differ the mutator is rejected with
    // 403 error.notification.forbidden and NO state change is persisted (no save, no delete, read
    // flag untouched); enforced by the acting-user id, never via project-scoping. The recipient-
    // scoped reads (listForActingUser / unreadCount) only ever query the acting user's own rows.
    // Validates: Requirements 10.9, 10.19, 13.8, 13.9, 13.13
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    @Tag("Feature: FOR-05-07-offer-approval, Property 18: Notification access is limited to the acting user's own notifications")
    void foreignNotificationAccessIsRejectedOwnIsPermitted(
            @ForAll @LongRange(min = 1, max = 1_000_000) long ownerId,
            @ForAll @LongRange(min = 1, max = 1_000_000) long actingUserId,
            @ForAll boolean initiallyRead,
            @ForAll boolean toggleNotDelete) {

        ServiceFixture f = new ServiceFixture();
        NotificationEntity notification = notificationOwnedBy(ownerId, initiallyRead);
        when(f.notificationDao.findById(NOTIFICATION_ID)).thenReturn(Optional.of(notification));

        if (ownerId == actingUserId) {
            // Owned: the mutator succeeds and persists.
            if (toggleNotDelete) {
                NotificationEntity result = f.service.toggleRead(actingUserId, NOTIFICATION_ID);
                assertThat(result.isRead()).isEqualTo(!initiallyRead);
                verify(f.notificationDao).save(notification);
            } else {
                f.service.delete(actingUserId, NOTIFICATION_ID);
                verify(f.notificationDao).delete(notification);
            }
        } else {
            // Foreign: rejected 403 with no state change.
            ForemenApiException ex = catchThrowableOfType(
                    () -> {
                        if (toggleNotDelete) {
                            f.service.toggleRead(actingUserId, NOTIFICATION_ID);
                        } else {
                            f.service.delete(actingUserId, NOTIFICATION_ID);
                        }
                    },
                    ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getMessageCode()).isEqualTo(FORBIDDEN_MESSAGE);
            // No mutation persisted and the read flag is untouched.
            verify(f.notificationDao, never()).save(any());
            verify(f.notificationDao, never()).delete(any(NotificationEntity.class));
            assertThat(notification.isRead()).isEqualTo(initiallyRead);
        }
    }

    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    @Tag("Feature: FOR-05-07-offer-approval, Property 18: Notification access is limited to the acting user's own notifications")
    void readsOnlyEverQueryTheActingUsersOwnRows(
            @ForAll @LongRange(min = 1, max = 1_000_000) long actingUserId) {

        ServiceFixture f = new ServiceFixture();
        // The recipient-scoped finder is the ONLY source of listed rows; stub it to a single row
        // owned by the acting user so any foreign row could only appear via a non-scoped query.
        NotificationEntity own = notificationOwnedBy(actingUserId, false);
        when(f.notificationDao.findByRecipientIdOrderByCreatedDateDesc(actingUserId))
                .thenReturn(List.of(own));

        List<NotificationEntity> listed = f.service.listForActingUser(actingUserId);

        // Every returned row is owned by the acting user, and the recipient-scoped finder was called
        // with exactly the acting user's id (never a project-scoped or unscoped finder).
        assertThat(listed).isNotEmpty();
        assertThat(listed).allSatisfy(n ->
                assertThat(n.getRecipient().getId()).isEqualTo(actingUserId));
        verify(f.notificationDao).findByRecipientIdOrderByCreatedDateDesc(actingUserId);
    }

    // ------------------------------------------------------------------------------------------
    // Property 19: The bell unread badge count equals the user's unread notifications.
    // For any acting user and any non-negative unread count, unreadCount(actingUserId) returns
    // exactly the recipient-scoped unread count and queries it with the acting user's own id.
    // Validates: Requirements 10.10
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 19: The bell unread badge count equals the user's unread notifications")
    void unreadCountEqualsRecipientScopedUnreadCount(
            @ForAll @LongRange(min = 1, max = 1_000_000) long actingUserId,
            @ForAll @LongRange(min = 0, max = 10_000) long unread) {

        ServiceFixture f = new ServiceFixture();
        when(f.notificationDao.countByRecipientIdAndReadFalse(actingUserId)).thenReturn(unread);

        long badge = f.service.unreadCount(actingUserId);

        assertThat(badge).isEqualTo(unread);
        // The count came from the recipient-scoped finder keyed by the acting user's own id.
        verify(f.notificationDao).countByRecipientIdAndReadFalse(actingUserId);
    }

    // ------------------------------------------------------------------------------------------
    // Property 20: A failed notification emission never blocks the triggering transition.
    // For any negotiation trigger, when NotificationService.create throws,
    // OfferNotificationEmitter.onOfferNegotiation swallows the exception and does NOT propagate it
    // (the listener is AFTER_COMMIT + log-only-on-failure), so a failed emission can never roll back
    // or block the already-committed negotiation transition.
    // Validates: Requirements 10.11, 14.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    @Tag("Feature: FOR-05-07-offer-approval, Property 20: A failed notification emission never blocks the triggering transition")
    void failedEmissionIsSwallowedForEveryTrigger(
            @ForAll("events") OfferNegotiationNotificationEvent event) {

        EmitterFixture f = new EmitterFixture();
        // create() always throws for this property — the emitter must swallow it whichever trigger
        // routes to it.
        when(f.notificationService.create(anyLong(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("simulated notification failure"));

        // The emitter must not propagate — the committed transition is never blocked.
        assertThatCode(() -> f.emitter.onOfferNegotiation(event)).doesNotThrowAnyException();

        // The failing create was actually attempted (so the swallow is exercised, not skipped).
        verify(f.notificationService).create(anyLong(), anyString(), any(), any());
    }

    // ---- Fixtures ----

    /** A {@link NotificationService} over mocked DAOs; {@code save} echoes its argument back. */
    private static final class ServiceFixture {
        final NotificationDao notificationDao = mock(NotificationDao.class);
        final UserDao userDao = mock(UserDao.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final NotificationService service;

        ServiceFixture() {
            // entityManager.flush() is a void no-op on the mock by default; nothing to stub.
            lenient().when(notificationDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
            service = new NotificationService(notificationDao, userDao, entityManager);
        }
    }

    /**
     * An {@link OfferNotificationEmitter} over a throwing {@link NotificationService} and a
     * {@link ProjectMemberDao} that resolves a MANAGER member (so the manager-facing trigger reaches
     * {@code create}); the client-facing triggers reach {@code create} via the event's requesting
     * client id.
     */
    private static final class EmitterFixture {
        final NotificationService notificationService = mock(NotificationService.class);
        final ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
        final OfferNotificationEmitter emitter;

        EmitterFixture() {
            lenient().when(projectMemberDao.findByProjectId(anyLong()))
                    .thenReturn(managerMember());
            emitter = new OfferNotificationEmitter(notificationService, projectMemberDao);
        }
    }

    // ---- Helpers ----

    private static NotificationEntity notificationOwnedBy(long ownerId, boolean read) {
        UserEntity owner = new UserEntity();
        owner.setId(ownerId);
        NotificationEntity notification = new NotificationEntity();
        notification.setId(NOTIFICATION_ID);
        notification.setRecipient(owner);
        notification.setType("notification.offer.discount.request");
        notification.setRead(read);
        return notification;
    }

    /** A single-member list holding a MANAGER member with a resolvable user id. */
    private static List<ProjectMemberEntity> managerMember() {
        UserEntity user = new UserEntity();
        user.setId(4242L);
        RoleEntity role = new RoleEntity();
        role.setCode(MANAGER_ROLE);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectRole(role);
        List<ProjectMemberEntity> members = new ArrayList<>();
        members.add(member);
        return members;
    }

    // ---- Generators ----

    /**
     * Arbitrary negotiation-notification events across all three triggers, with non-null
     * project/offer ids and a non-null requesting-client id for the client-facing triggers so each
     * trigger routes to {@code NotificationService.create}.
     */
    @Provide
    Arbitrary<OfferNegotiationNotificationEvent> events() {
        Arbitrary<Trigger> triggers = Arbitraries.of(Trigger.values());
        Arbitrary<Long> offerIds = Arbitraries.longs().between(1L, 1_000_000L);
        Arbitrary<Long> projectIds = Arbitraries.longs().between(1L, 1_000_000L);
        Arbitrary<Long> clientIds = Arbitraries.longs().between(1L, 1_000_000L);
        return Combinators.combine(triggers, offerIds, projectIds, clientIds)
                .as((trigger, offerId, projectId, clientId) ->
                        new OfferNegotiationNotificationEvent(trigger, offerId, projectId, clientId));
    }
}
