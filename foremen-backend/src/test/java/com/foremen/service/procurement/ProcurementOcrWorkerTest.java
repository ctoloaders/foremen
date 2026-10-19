package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.model.ProcessingStatus;
import com.foremen.service.ProcurementDocumentService;
import com.foremen.service.ProcurementDocumentService.NotificationContext;

/**
 * FOR-06-03 Amendment A1 (R14.3, R15, R17) — unit tests for {@link ProcurementOcrWorker}, the async
 * OCR worker, with a mocked {@link ProcurementDocumentService} and
 * {@link ProcurementOcrNotificationEmitter}.
 *
 * <p>Validates: a null/blank event is a no-op; a PARSED terminal run forwards the stage sink to the
 * service and emits the terminal notification with the uploader/project/doc context; a FAILED run
 * emits the failed notification with the failure reason; a null (document-gone) or non-terminal
 * (disabled-provider) result emits nothing; and an unexpected service throw is swallowed (the sweep
 * recovers it).
 */
@ExtendWith(MockitoExtension.class)
class ProcurementOcrWorkerTest {

    private static final Long DOC_ID = 500L;
    private static final Long UPLOADER = 77L;
    private static final Long PROJECT = 42L;

    @Mock
    private ProcurementDocumentService service;
    @Mock
    private ProcurementOcrNotificationEmitter notificationEmitter;

    private ProcurementOcrWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ProcurementOcrWorker(service, notificationEmitter);
    }

    @Test
    @DisplayName("a null or id-less event is a no-op")
    void nullEventIsNoOp() {
        worker.onOcrRequested(null);
        worker.onOcrRequested(new ProcurementOcrRequested(null));

        verifyNoInteractions(service);
        verifyNoInteractions(notificationEmitter);
    }

    @Test
    @DisplayName("PARSED: drives the service stage sink and emits the parsed terminal notification (R15/R17.1)")
    void parsedDrivesStagesAndNotifies() {
        when(service.processQueued(eq(DOC_ID), any())).thenReturn(ProcessingStatus.PARSED);
        when(service.notificationContext(DOC_ID))
                .thenReturn(new NotificationContext(UPLOADER, PROJECT, null));

        worker.onOcrRequested(new ProcurementOcrRequested(DOC_ID));

        verify(service).processQueued(eq(DOC_ID), any());
        verify(notificationEmitter).emitTerminal(ProcessingStatus.PARSED, UPLOADER, PROJECT, DOC_ID, null);
    }

    @Test
    @DisplayName("the stage sink the worker passes commits each stage via service.markStage (R15.2)")
    void stageSinkCommitsStages() {
        when(service.processQueued(eq(DOC_ID), any())).thenAnswer(inv -> {
            Consumer<ProcessingStatus> sink = inv.getArgument(1);
            sink.accept(ProcessingStatus.RASTERIZING);
            sink.accept(ProcessingStatus.CLEANING);
            sink.accept(ProcessingStatus.MATCHING);
            return ProcessingStatus.PARSED;
        });
        when(service.notificationContext(DOC_ID))
                .thenReturn(new NotificationContext(UPLOADER, PROJECT, null));

        worker.onOcrRequested(new ProcurementOcrRequested(DOC_ID));

        ArgumentCaptor<ProcessingStatus> stages = ArgumentCaptor.forClass(ProcessingStatus.class);
        verify(service, org.mockito.Mockito.times(3)).markStage(eq(DOC_ID), stages.capture());
        List<ProcessingStatus> captured = new ArrayList<>(stages.getAllValues());
        org.assertj.core.api.Assertions.assertThat(captured).containsExactly(
                ProcessingStatus.RASTERIZING, ProcessingStatus.CLEANING, ProcessingStatus.MATCHING);
    }

    @Test
    @DisplayName("FAILED: emits the failed terminal notification with the failure reason (R17.2)")
    void failedNotifiesWithReason() {
        when(service.processQueued(eq(DOC_ID), any())).thenReturn(ProcessingStatus.FAILED);
        when(service.notificationContext(DOC_ID))
                .thenReturn(new NotificationContext(UPLOADER, PROJECT, "procurement.ocr.failed.vision"));

        worker.onOcrRequested(new ProcurementOcrRequested(DOC_ID));

        verify(notificationEmitter).emitTerminal(
                ProcessingStatus.FAILED, UPLOADER, PROJECT, DOC_ID, "procurement.ocr.failed.vision");
    }

    @Test
    @DisplayName("a null (document-gone) result emits no notification")
    void documentGoneEmitsNothing() {
        when(service.processQueued(eq(DOC_ID), any())).thenReturn(null);

        worker.onOcrRequested(new ProcurementOcrRequested(DOC_ID));

        verify(notificationEmitter, never()).emitTerminal(any(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("a non-terminal UPLOADED result (disabled provider) emits no notification (R17.4)")
    void uploadedRestingEmitsNothing() {
        when(service.processQueued(eq(DOC_ID), any())).thenReturn(ProcessingStatus.UPLOADED);

        worker.onOcrRequested(new ProcurementOcrRequested(DOC_ID));

        verify(notificationEmitter, never()).emitTerminal(any(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("an unexpected service throw is swallowed (the sweep recovers it, R18.4)")
    void unexpectedThrowIsSwallowed() {
        when(service.processQueued(eq(DOC_ID), any())).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> worker.onOcrRequested(new ProcurementOcrRequested(DOC_ID)))
                .doesNotThrowAnyException();

        verify(notificationEmitter, never()).emitTerminal(any(), anyLong(), anyLong(), anyLong(), any());
    }
}
