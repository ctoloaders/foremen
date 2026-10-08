package com.foremen.service.document;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.service.document.DocumentNotificationEvent.Trigger;
import com.foremen.service.offer.NotificationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link DocumentNotificationEmitter} (FOR-05-08-document-signing, task 9.2),
 * exercising the best-effort {@code onDocumentSigning} consumer in isolation with a mocked
 * {@link NotificationService} — following the {@code DocumentMediaServiceTest} Mockito convention
 * (a {@code @ExtendWith(MockitoExtension.class)} plain-object unit test, no Spring context).
 *
 * <p>The emitter is a pure mapper: it turns one {@link DocumentNotificationEvent} into one
 * {@link NotificationService#create(Long, String, String, String)} call per recipient, mapping the
 * {@link Trigger} to its {@code Notification_Type} i18n key and attaching the signing-tab deep-link
 * {@code /projects/{projectId}/documentSigning} (R11.2, R11.3). These tests assert:
 *
 * <ul>
 *   <li>each of the five triggers maps to the correct type key and creates exactly one notification
 *       per recipient with the right deep-link (R11.2);</li>
 *   <li>a {@link NotificationService#create} that throws is swallowed — no exception propagates — and
 *       one failing recipient never starves the remaining recipients (R11.1 best-effort);</li>
 *   <li>an event with an empty recipient set emits nothing (R11.2 — empty is not an error);</li>
 *   <li>a {@code null} event is a no-op;</li>
 *   <li>no event ⇒ no emission for DRAFT edits is structural (the DRAFT paths never publish an
 *       event), so the emitter is only asserted to emit per the event it is handed (R11.5).</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 11.2, 11.5</b>
 */
@ExtendWith(MockitoExtension.class)
class DocumentNotificationEmitterTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private DocumentNotificationEmitter emitter;

    private static final Long DOCUMENT_ID = 500L;
    private static final Long PROJECT_ID = 42L;
    private static final String TYPE_CODE = "CONTRACT_WORKS";
    private static final String TITLE = "Umowa 2026/01";
    private static final String EXPECTED_DEEP_LINK = "/projects/42/documentSigning";

    // ------------------------------------------------------------------------------------------
    // R11.2 — each trigger maps to the right type + one notification per recipient + deep-link
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("DOCUMENT_SENT_FOR_SIGNING maps to notification.document.sentForSigning (R11.2)")
    void sentForSigningMapsToType() {
        emitter.onDocumentSigning(event(Trigger.DOCUMENT_SENT_FOR_SIGNING, List.of(1L, 2L)));

        verify(notificationService).create(eq(1L), eq("notification.document.sentForSigning"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService).create(eq(2L), eq("notification.document.sentForSigning"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DOCUMENT_SIGNED_BY_PARTY maps to notification.document.signedByParty (R11.2)")
    void signedByPartyMapsToType() {
        emitter.onDocumentSigning(event(Trigger.DOCUMENT_SIGNED_BY_PARTY, List.of(7L)));

        verify(notificationService).create(eq(7L), eq("notification.document.signedByParty"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(1)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DOCUMENT_FULLY_SIGNED maps to notification.document.fullySigned for every recipient (R11.2)")
    void fullySignedMapsToType() {
        emitter.onDocumentSigning(event(Trigger.DOCUMENT_FULLY_SIGNED, List.of(7L, 1L, 2L)));

        verify(notificationService).create(eq(7L), eq("notification.document.fullySigned"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService).create(eq(1L), eq("notification.document.fullySigned"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService).create(eq(2L), eq("notification.document.fullySigned"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(3)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DOCUMENT_SIGNING_DECLINED maps to notification.document.signingDeclined (R11.2)")
    void signingDeclinedMapsToType() {
        emitter.onDocumentSigning(event(Trigger.DOCUMENT_SIGNING_DECLINED, List.of(7L)));

        verify(notificationService).create(eq(7L), eq("notification.document.signingDeclined"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(1)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("DOCUMENT_VOIDED maps to notification.document.voided for every pending signer (R11.2)")
    void voidedMapsToType() {
        emitter.onDocumentSigning(event(Trigger.DOCUMENT_VOIDED, List.of(1L, 2L)));

        verify(notificationService).create(eq(1L), eq("notification.document.voided"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService).create(eq(2L), eq("notification.document.voided"),
                anyString(), eq(EXPECTED_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("every trigger produces exactly one notification per recipient with the right type + deep-link (R11.2)")
    void everyTriggerMapsOneNotificationPerRecipient() {
        record Case(Trigger trigger, String type) { }
        List<Case> cases = List.of(
                new Case(Trigger.DOCUMENT_SENT_FOR_SIGNING, "notification.document.sentForSigning"),
                new Case(Trigger.DOCUMENT_SIGNED_BY_PARTY, "notification.document.signedByParty"),
                new Case(Trigger.DOCUMENT_FULLY_SIGNED, "notification.document.fullySigned"),
                new Case(Trigger.DOCUMENT_SIGNING_DECLINED, "notification.document.signingDeclined"),
                new Case(Trigger.DOCUMENT_VOIDED, "notification.document.voided"));

        for (Case c : cases) {
            org.mockito.Mockito.reset(notificationService);
            emitter.onDocumentSigning(event(c.trigger(), List.of(3L, 4L)));

            verify(notificationService).create(eq(3L), eq(c.type()), anyString(), eq(EXPECTED_DEEP_LINK));
            verify(notificationService).create(eq(4L), eq(c.type()), anyString(), eq(EXPECTED_DEEP_LINK));
            verify(notificationService, times(2)).create(anyLong(), anyString(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("deep-link is /projects/{projectId}/documentSigning scoped to the event's project (R11.3)")
    void deepLinkTargetsSigningTabForProject() {
        DocumentNotificationEvent event = new DocumentNotificationEvent(
                Trigger.DOCUMENT_SENT_FOR_SIGNING, DOCUMENT_ID, 99L, TYPE_CODE, TITLE, List.of(1L));

        emitter.onDocumentSigning(event);

        verify(notificationService).create(eq(1L), anyString(), anyString(),
                eq("/projects/99/documentSigning"));
    }

    @Test
    @DisplayName("message body identifies the document (type code + title) (R11.3)")
    void bodyIdentifiesDocument() {
        emitter.onDocumentSigning(event(Trigger.DOCUMENT_SENT_FOR_SIGNING, List.of(1L)));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(1L), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains(TYPE_CODE).contains(TITLE);
    }

    // ------------------------------------------------------------------------------------------
    // R11.1 — a failing create is swallowed and does not starve the remaining recipients
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a NotificationService.create failure is swallowed — no exception propagates (R11.1)")
    void createFailureSwallowed() {
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(anyLong(), anyString(), anyString(), anyString());

        assertThatCode(() -> emitter.onDocumentSigning(event(Trigger.DOCUMENT_SENT_FOR_SIGNING, List.of(1L))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one failing recipient does not starve the remaining recipients (R11.1)")
    void oneFailingRecipientDoesNotStarveOthers() {
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(eq(2L), anyString(), anyString(), anyString());

        assertThatCode(() -> emitter.onDocumentSigning(
                event(Trigger.DOCUMENT_FULLY_SIGNED, List.of(1L, 2L, 3L))))
                .doesNotThrowAnyException();

        // All three are attempted even though the middle one throws.
        verify(notificationService).create(eq(1L), anyString(), anyString(), anyString());
        verify(notificationService).create(eq(2L), anyString(), anyString(), anyString());
        verify(notificationService).create(eq(3L), anyString(), anyString(), anyString());
        verify(notificationService, times(3)).create(anyLong(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------------------------------
    // empty recipients / null event / null recipient-id edge cases
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an event with no recipients emits nothing (R11.2 — empty set is not an error)")
    void emptyRecipientsEmitsNothing() {
        emitter.onDocumentSigning(event(Trigger.DOCUMENT_SENT_FOR_SIGNING, List.of()));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a null event is a no-op (no emission for an absent event — DRAFT edits never publish, R11.5)")
    void nullEventIsNoOp() {
        assertThatCode(() -> emitter.onDocumentSigning(null)).doesNotThrowAnyException();

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a null project id yields a null deep-link but still emits (R11.3 non-interactive)")
    void nullProjectIdYieldsNullDeepLink() {
        DocumentNotificationEvent event = new DocumentNotificationEvent(
                Trigger.DOCUMENT_SENT_FOR_SIGNING, DOCUMENT_ID, null, TYPE_CODE, TITLE, List.of(1L));

        emitter.onDocumentSigning(event);

        verify(notificationService).create(eq(1L), eq("notification.document.sentForSigning"),
                anyString(), eq((String) null));
    }

    // ------------------------------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------------------------------

    private static DocumentNotificationEvent event(Trigger trigger, List<Long> recipientUserIds) {
        return new DocumentNotificationEvent(
                trigger, DOCUMENT_ID, PROJECT_ID, TYPE_CODE, TITLE, recipientUserIds);
    }
}
