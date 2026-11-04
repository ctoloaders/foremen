package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.service.offer.NotificationService;

/** FOR-07-03 (R12.2) - unit tests for {@link ProcurementRelinkNotificationEmitter}. */
@ExtendWith(MockitoExtension.class)
class ProcurementRelinkNotificationEmitterTest {

    @Mock
    private NotificationService notificationService;

    private ProcurementRelinkNotificationEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new ProcurementRelinkNotificationEmitter(notificationService);
    }

    private static ProcurementRelinkedEvent event(Long uploader, Long actor, String supplier, String invoice) {
        return new ProcurementRelinkedEvent(500L, uploader, actor, 42L, "Villa", supplier, invoice);
    }

    @Test
    @DisplayName("notifies the uploader with the relinked type, body and project deep link")
    void notifiesUploader() {
        emitter.emit(event(7L, 8L, "Acme", "FV/1"));

        verify(notificationService).create(eq(7L), eq("notification.procurement.relinked"),
                eq("Acme -> Villa"), eq("/projects/42/procurement?doc=500"));
    }

    @Test
    @DisplayName("supplier falls back to invoice number, then to #id")
    void bodyFallbacks() {
        emitter.emit(event(7L, 8L, " ", "FV/1"));
        verify(notificationService).create(eq(7L), anyString(), eq("FV/1 -> Villa"), anyString());

        emitter.emit(event(7L, 8L, null, null));
        verify(notificationService).create(eq(7L), anyString(), eq("#500 -> Villa"), anyString());
    }

    @Test
    @DisplayName("skipped when the actor is the uploader or there is no uploader")
    void skipsSelfAndMissingUploader() {
        emitter.emit(event(7L, 7L, "Acme", null));
        emitter.emit(event(null, 8L, "Acme", null));
        emitter.emit(null);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a failing NotificationService.create is swallowed (best-effort)")
    void failureSwallowed() {
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(anyLong(), anyString(), anyString(), anyString());

        assertThatCode(() -> emitter.emit(event(7L, 8L, "Acme", null))).doesNotThrowAnyException();
    }
}
