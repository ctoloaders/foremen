package com.foremen.service.execution.amendment;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.service.execution.amendment.AmendmentLifecycleEvent.Trigger;
import com.foremen.service.offer.NotificationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link AmendmentNotificationEmitter} (FOR-06-09-amendment-signing, task 3.2),
 * exercising the best-effort {@code onAmendmentLifecycle} consumer in isolation with a mocked
 * {@link NotificationService} — mirroring the {@code DocumentNotificationEmitterTest} Mockito
 * convention (an {@code @ExtendWith(MockitoExtension.class)} plain-object unit test, no Spring
 * context).
 *
 * <p>The emitter is a pure mapper: it turns one {@link AmendmentLifecycleEvent} into one
 * {@link NotificationService#create(Long, String, String, String)} call per recipient, mapping the
 * {@link Trigger} to its {@code notification.amendment.*} i18n key and attaching the clickable
 * Aneksy-tab deep-link {@code /projects/{projectId}/amendments} (R7.3, R7.5). These tests assert:
 *
 * <ul>
 *   <li>each of the three triggers maps to the correct {@code notification.amendment.*} type key and
 *       creates exactly one notification per recipient (amendment owner MANAGER + project FINANCIER)
 *       with the clickable Aneksy deep-link (R7.3, R7.5);</li>
 *   <li>the emitter emits <b>no</b> {@code document.*} type and no second CLIENT-facing notification —
 *       the CLIENT ceremony is covered by the reused FOR-05-08 {@code notification.document.*}
 *       (R7.2, R7.4);</li>
 *   <li>a {@link NotificationService#create} that throws is swallowed — no exception propagates — and
 *       one failing recipient never starves the remaining recipients (R7.1, R7.6 best-effort);</li>
 *   <li>an event with an empty recipient set emits nothing and a {@code null} event is a no-op.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 7.2, 7.3, 7.4, 7.5, 7.6</b>
 */
@ExtendWith(MockitoExtension.class)
class AmendmentNotificationEmitterTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private AmendmentNotificationEmitter emitter;

    private static final Long AMENDMENT_ID = 700L;
    private static final Long PROJECT_ID = 42L;
    private static final Integer SEQ_NO = 3;
    private static final String EXPECTED_DEEP_LINK = "/projects/42/amendments";

    /** Amendment owner (MANAGER) + project FINANCIER — the R7.3 recipient set for every trigger. */
    private static final Long OWNER_MANAGER_ID = 11L;
    private static final Long FINANCIER_ID = 22L;
    private static final List<Long> RECIPIENTS = List.of(OWNER_MANAGER_ID, FINANCIER_ID);

    // ------------------------------------------------------------------------------------------
    // R7.3 — each trigger maps to the right notification.amendment.* type for both recipients
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SENT_FOR_SIGNING maps to notification.amendment.sentForSigning for owner + financier (R7.3)")
    void sentForSigningMapsToType() {
        emitter.onAmendmentLifecycle(event(Trigger.SENT_FOR_SIGNING, RECIPIENTS));

        verify(notificationService).create(eq(OWNER_MANAGER_ID),
                eq("notification.amendment.sentForSigning"), anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_ID),
                eq("notification.amendment.sentForSigning"), anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("REOPENED maps to notification.amendment.reopened for owner + financier (R7.3)")
    void reopenedMapsToType() {
        emitter.onAmendmentLifecycle(event(Trigger.REOPENED, RECIPIENTS));

        verify(notificationService).create(eq(OWNER_MANAGER_ID),
                eq("notification.amendment.reopened"), anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_ID),
                eq("notification.amendment.reopened"), anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("EFFECTIVE maps to notification.amendment.effective for owner + financier (R7.3)")
    void effectiveMapsToType() {
        emitter.onAmendmentLifecycle(event(Trigger.EFFECTIVE, RECIPIENTS));

        verify(notificationService).create(eq(OWNER_MANAGER_ID),
                eq("notification.amendment.effective"), anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_ID),
                eq("notification.amendment.effective"), anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("every trigger produces exactly one notification per recipient with the right type (R7.3)")
    void everyTriggerMapsOneNotificationPerRecipient() {
        record Case(Trigger trigger, String type) { }
        List<Case> cases = List.of(
                new Case(Trigger.SENT_FOR_SIGNING, "notification.amendment.sentForSigning"),
                new Case(Trigger.REOPENED, "notification.amendment.reopened"),
                new Case(Trigger.EFFECTIVE, "notification.amendment.effective"));

        for (Case c : cases) {
            org.mockito.Mockito.reset(notificationService);
            emitter.onAmendmentLifecycle(event(c.trigger(), RECIPIENTS));

            verify(notificationService).create(eq(OWNER_MANAGER_ID), eq(c.type()), anyString(),
                    eq(EXPECTED_DEEP_LINK));
            verify(notificationService).create(eq(FINANCIER_ID), eq(c.type()), anyString(),
                    eq(EXPECTED_DEEP_LINK));
            verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
        }
    }

    // ------------------------------------------------------------------------------------------
    // R7.2 / R7.4 — only notification.amendment.* is emitted; never a document.* duplicate and
    // never a second CLIENT-facing notification for the same event
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("every trigger emits only a notification.amendment.* type — never a document.* duplicate (R7.2)")
    void emitsOnlyAmendmentTypesNeverDocumentTypes() {
        for (Trigger trigger : Trigger.values()) {
            org.mockito.Mockito.reset(notificationService);
            emitter.onAmendmentLifecycle(event(trigger, RECIPIENTS));

            // Every emitted type starts with notification.amendment.
            verify(notificationService, times(RECIPIENTS.size()))
                    .create(anyLong(), startsWith("notification.amendment."), anyString(), anyString());
            // and no notification.document.* type is ever emitted.
            verify(notificationService, never())
                    .create(anyLong(), contains("notification.document."), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("recipients are the amendment owner + financier only — no second CLIENT notification (R7.4)")
    void noSecondClientFacingNotification() {
        Long clientUserId = 999L;

        // The publisher resolves recipients to owner MANAGER + project FINANCIER only; the CLIENT is
        // never in the amendment recipient set (the CLIENT ceremony is the reused document.* notif).
        emitter.onAmendmentLifecycle(event(Trigger.SENT_FOR_SIGNING, RECIPIENTS));

        verify(notificationService).create(eq(OWNER_MANAGER_ID), anyString(), anyString(), anyString());
        verify(notificationService).create(eq(FINANCIER_ID), anyString(), anyString(), anyString());
        verify(notificationService, never()).create(eq(clientUserId), anyString(), anyString(), anyString());
        verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // R7.5 — the notification identifies the amendment (project + seqNo) and is clickable
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the deep-link is the clickable Aneksy tab /projects/{projectId}/amendments (R7.3, R7.5)")
    void deepLinkTargetsAneksyTabForProject() {
        AmendmentLifecycleEvent event = new AmendmentLifecycleEvent(
                Trigger.SENT_FOR_SIGNING, AMENDMENT_ID, 99L, SEQ_NO, List.of(OWNER_MANAGER_ID));

        emitter.onAmendmentLifecycle(event);

        verify(notificationService).create(eq(OWNER_MANAGER_ID), anyString(), anyString(),
                eq("/projects/99/amendments"));
    }

    @Test
    @DisplayName("message body identifies the amendment by project + seqNo (R7.5)")
    void bodyIdentifiesAmendment() {
        emitter.onAmendmentLifecycle(event(Trigger.SENT_FOR_SIGNING, List.of(OWNER_MANAGER_ID)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(OWNER_MANAGER_ID), anyString(), body.capture(), anyString());
        assertThat(body.getValue())
                .contains(String.valueOf(PROJECT_ID))
                .contains(String.valueOf(SEQ_NO));
    }

    // ------------------------------------------------------------------------------------------
    // R7.1 / R7.6 — a failing create is swallowed and does not starve the remaining recipients
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a NotificationService.create failure is swallowed — no exception propagates (R7.6)")
    void createFailureSwallowed() {
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(anyLong(), anyString(), anyString(), anyString());

        assertThatCode(() -> emitter.onAmendmentLifecycle(event(Trigger.EFFECTIVE, RECIPIENTS)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one failing recipient does not starve the remaining recipients (R7.6)")
    void oneFailingRecipientDoesNotStarveOthers() {
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(eq(FINANCIER_ID), anyString(), anyString(), anyString());

        assertThatCode(() -> emitter.onAmendmentLifecycle(
                event(Trigger.EFFECTIVE, List.of(OWNER_MANAGER_ID, FINANCIER_ID, 33L))))
                .doesNotThrowAnyException();

        // All three are attempted even though the middle one throws.
        verify(notificationService).create(eq(OWNER_MANAGER_ID), anyString(), anyString(), anyString());
        verify(notificationService).create(eq(FINANCIER_ID), anyString(), anyString(), anyString());
        verify(notificationService).create(eq(33L), anyString(), anyString(), anyString());
        verify(notificationService, times(3)).create(anyLong(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // empty recipients / null event edge cases
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an event with no recipients emits nothing (empty set is not an error)")
    void emptyRecipientsEmitsNothing() {
        emitter.onAmendmentLifecycle(event(Trigger.SENT_FOR_SIGNING, List.of()));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a null event is a no-op")
    void nullEventIsNoOp() {
        assertThatCode(() -> emitter.onAmendmentLifecycle(null)).doesNotThrowAnyException();

        verifyNoInteractions(notificationService);
    }

    // ------------------------------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------------------------------

    private static AmendmentLifecycleEvent event(Trigger trigger, List<Long> recipientUserIds) {
        return new AmendmentLifecycleEvent(trigger, AMENDMENT_ID, PROJECT_ID, SEQ_NO, recipientUserIds);
    }
}
