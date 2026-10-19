package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.model.ProcessingStatus;
import com.foremen.service.offer.NotificationService;

/**
 * FOR-06-03 Amendment A1 (R17) — plain Mockito unit tests for
 * {@link ProcurementOcrNotificationEmitter}, mirroring {@code PaymentNotificationEmitterTest}.
 *
 * <p>Validates: a PARSED terminal state notifies the uploader with
 * {@code notification.procurement.ocr.parsed} + the procurement deep-link and a null body; a FAILED
 * state notifies with {@code .failed} + the failure reason as the body; a non-terminal / UPLOADED
 * state or a null recipient notifies nobody; and a failing {@code NotificationService.create} is
 * swallowed (best-effort, never alters the committed terminal state).
 */
@ExtendWith(MockitoExtension.class)
class ProcurementOcrNotificationEmitterTest {

    private static final Long UPLOADER_UID = 77L;
    private static final Long PROJECT_ID = 42L;
    private static final Long DOCUMENT_ID = 500L;
    private static final String DEEP_LINK = "/projects/42/procurement?doc=500";

    @Mock
    private NotificationService notificationService;

    private ProcurementOcrNotificationEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new ProcurementOcrNotificationEmitter(notificationService);
    }

    @Test
    @DisplayName("PARSED notifies the uploader with the parsed type, procurement deep-link, null body (R17.1)")
    void parsedNotifiesUploader() {
        emitter.emitTerminal(ProcessingStatus.PARSED, UPLOADER_UID, PROJECT_ID, DOCUMENT_ID, null);

        verify(notificationService).create(eq(UPLOADER_UID),
                eq("notification.procurement.ocr.parsed"), isNull(), eq(DEEP_LINK));
    }

    @Test
    @DisplayName("FAILED notifies the uploader with the failed type + failure reason as body (R17.2)")
    void failedNotifiesUploaderWithReason() {
        emitter.emitTerminal(ProcessingStatus.FAILED, UPLOADER_UID, PROJECT_ID, DOCUMENT_ID,
                "procurement.ocr.failed.vision");

        verify(notificationService).create(eq(UPLOADER_UID),
                eq("notification.procurement.ocr.failed"), eq("procurement.ocr.failed.vision"),
                eq(DEEP_LINK));
    }

    @Test
    @DisplayName("a non-terminal / UPLOADED status notifies nobody (R17.4)")
    void nonTerminalNotifiesNobody() {
        emitter.emitTerminal(ProcessingStatus.UPLOADED, UPLOADER_UID, PROJECT_ID, DOCUMENT_ID, null);
        emitter.emitTerminal(ProcessingStatus.QUEUED, UPLOADER_UID, PROJECT_ID, DOCUMENT_ID, null);
        emitter.emitTerminal(ProcessingStatus.MATCHING, UPLOADER_UID, PROJECT_ID, DOCUMENT_ID, null);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a null recipient notifies nobody (best-effort guard)")
    void nullRecipientNotifiesNobody() {
        emitter.emitTerminal(ProcessingStatus.PARSED, null, PROJECT_ID, DOCUMENT_ID, null);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a failing NotificationService.create is swallowed (R17.3 best-effort)")
    void failingCreateIsSwallowed() {
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(anyLong(), anyString(), isNull(), anyString());

        assertThatCode(() -> emitter.emitTerminal(
                ProcessingStatus.PARSED, UPLOADER_UID, PROJECT_ID, DOCUMENT_ID, null))
                .doesNotThrowAnyException();

        verify(notificationService).create(eq(UPLOADER_UID),
                eq("notification.procurement.ocr.parsed"), isNull(), eq(DEEP_LINK));
    }
}
