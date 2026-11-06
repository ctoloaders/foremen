package com.foremen.service.roomsimport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.service.roomsimport.model.ImportResult;
import com.foremen.service.roomsimport.model.ModelInfo;

/** Unit tests of {@link RoomsImportWorker}. Validates: Requirements 16.2, 16.3, 24.3. */
class RoomsImportWorkerTest {

    private final RoomsImportPipeline pipeline = mock(RoomsImportPipeline.class);
    private final RoomsImportJobTerminator terminator = mock(RoomsImportJobTerminator.class);
    private final RoomsImportWorker worker = new RoomsImportWorker(pipeline, terminator);

    private static final ImportResult RESULT = new ImportResult(List.of(), List.of(), List.of(), new ModelInfo("m"));

    @Test
    void completedPipeline_completesTheJob() {
        when(pipeline.run(5L)).thenReturn(RESULT);

        worker.onRequested(new RoomsImportRequested(5L));

        verify(terminator).complete(5L, RESULT);
        verify(terminator, never()).fail(anyLong(), any());
    }

    @Test
    void cancelledJob_writesNothing() {
        when(pipeline.run(5L)).thenThrow(new JobCancelledException(5L));

        worker.onRequested(new RoomsImportRequested(5L));

        verifyNoInteractions(terminator);
    }

    @Test
    void pipelineFailure_failsTheJobWithItsReason() {
        when(pipeline.run(5L)).thenThrow(new RoomsImportFailureException(RoomsImportErrors.FAILED_LLM_UNAVAILABLE));

        worker.onRequested(new RoomsImportRequested(5L));

        verify(terminator).fail(5L, RoomsImportErrors.FAILED_LLM_UNAVAILABLE);
        verify(terminator, never()).complete(anyLong(), any());
    }

    @Test
    void unexpectedException_failsTheJobWithInternalReason() {
        when(pipeline.run(5L)).thenThrow(new IllegalStateException("boom"));

        worker.onRequested(new RoomsImportRequested(5L));

        verify(terminator).fail(5L, RoomsImportErrors.FAILED_INTERNAL);
    }

    @Test
    void terminatorFailureDoesNotEscape() {
        when(pipeline.run(5L)).thenThrow(new IllegalStateException("boom"));
        when(terminator.fail(5L, RoomsImportErrors.FAILED_INTERNAL)).thenThrow(new IllegalStateException("db down"));

        worker.onRequested(new RoomsImportRequested(5L)); // must not throw; the sweep recovers the job
    }

    @Test
    void nullEvent_isIgnored() {
        worker.onRequested(null);
        worker.onRequested(new RoomsImportRequested(null));

        verifyNoInteractions(pipeline, terminator);
    }
}
