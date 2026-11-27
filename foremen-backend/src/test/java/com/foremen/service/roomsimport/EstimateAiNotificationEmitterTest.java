package com.foremen.service.roomsimport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.foremen.service.offer.NotificationService;

/** Unit tests of {@link EstimateAiNotificationEmitter}. Validates: Requirements 16.1, 16.2, 16.4. */
class EstimateAiNotificationEmitterTest {

    private final NotificationService service = mock(NotificationService.class);
    private final EstimateAiNotificationEmitter emitter = new EstimateAiNotificationEmitter(service);

    @Test
    void completed_createsTypedNotificationWithNullBodyAndOfferDeepLink() {
        emitter.emitCompleted(5L, 9L);

        verify(service).create(9L, "notification.estimateAi.completed", null, "/projects/5/estimate");
    }

    @Test
    void failed_carriesReasonCodeAsBodyAndRoomsDeepLink() {
        emitter.emitFailed(5L, 9L, "error.roomsimport.failed.timeout");

        verify(service).create(9L, "notification.estimateAi.failed", "error.roomsimport.failed.timeout",
                "/projects/5/rooms");
    }

    @Test
    void serviceFailureIsSwallowed() {
        when(service.create(anyLong(), any(), any(), any())).thenThrow(new IllegalStateException("db"));

        emitter.emitCompleted(5L, 9L); // must not throw
        emitter.emitFailed(5L, 9L, "x");
    }

    @Test
    void nullRecipientIsSkipped() {
        emitter.emitCompleted(5L, null);
        emitter.emitFailed(5L, null, "x");

        verifyNoInteractions(service);
    }
}
