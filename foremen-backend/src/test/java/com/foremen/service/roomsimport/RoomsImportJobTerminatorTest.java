package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.roomsimport.model.ImportResult;
import com.foremen.service.roomsimport.model.ModelInfo;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;

/** Unit tests of {@link RoomsImportJobTerminator}. Validates: Requirements 17.5, 17.6, 17.8, 19.6 (design B2b). */
class RoomsImportJobTerminatorTest {

    private final RoomsImportJobDao dao = mock(RoomsImportJobDao.class);
    private final RoomsImportProperties props = new RoomsImportProperties(true, null, null, null, 0, null);
    private final RoomsImportNotificationEmitter emitter = mock(RoomsImportNotificationEmitter.class);
    private final RoomsImportJobTerminator terminator = new RoomsImportJobTerminator(dao,
            new RoomsImportJobMapper(new ObjectMapper().findAndRegisterModules()), props, emitter);

    private static final ImportResult RESULT = new ImportResult(List.of(),
            List.of(new Warning("NO_ROOMS_DETECTED", Severity.WARNING, null, null, java.util.Map.of(), null)),
            List.of(), new ModelInfo("gemini"));

    private static RoomsImportJobEntity jobOf(long projectId, long userId) {
        RoomsImportJobEntity job = new RoomsImportJobEntity();
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        UserEntity user = new UserEntity();
        user.setId(userId);
        job.setProject(project);
        job.setCreator(user);
        return job;
    }

    @Test
    void complete_whenStillActive_storesResultAndReturnsOne_andNotifiesStarter() {
        RoomsImportJobEntity job = jobOf(7L, 9L);
        when(dao.terminateIfActive(eq(4L), eq(RoomsImportStatus.COMPLETED), eq(null), any(), any(), eq(0), eq(1),
                eq("gemini"), eq(RoomsImportJobDao.TERMINAL))).thenReturn(1);
        when(dao.findById(4L)).thenReturn(Optional.of(job));

        int updated = terminator.complete(4L, RESULT);

        assertThat(updated).isEqualTo(1);
        assertThat(job.getResult()).containsKey("warnings");
        verify(dao).save(job);
        verify(emitter).emitCompleted(7L, 9L);
    }

    @Test
    void complete_whenCancelledOrAlreadyTerminal_changesNothingAndReturnsZero() {
        when(dao.terminateIfActive(anyLong(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(terminator.complete(4L, RESULT)).isZero();

        verify(dao, never()).findById(anyLong());
        verify(dao, never()).save(any());
        verifyNoInteractions(emitter);
    }

    @Test
    void fail_isStatusConditionalAndReturnsAffectedRows_andNotifiesOnlyTheWinner() {
        when(dao.terminateIfActive(eq(4L), eq(RoomsImportStatus.FAILED), eq(RoomsImportErrors.FAILED_TIMEOUT), any(),
                any(), eq(null), eq(null), eq(null), eq(RoomsImportJobDao.TERMINAL))).thenReturn(1, 0);
        when(dao.findById(4L)).thenReturn(Optional.of(jobOf(7L, 9L)));

        assertThat(terminator.fail(4L, RoomsImportErrors.FAILED_TIMEOUT)).isEqualTo(1);
        assertThat(terminator.fail(4L, RoomsImportErrors.FAILED_TIMEOUT)).isZero(); // lost the race

        verify(emitter, times(1)).emitFailed(7L, 9L, RoomsImportErrors.FAILED_TIMEOUT);
    }

    @Test
    void fail_zeroRows_noEmission() {
        when(dao.terminateIfActive(anyLong(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(terminator.fail(4L, "x")).isZero();

        verifyNoInteractions(emitter);
    }
}
