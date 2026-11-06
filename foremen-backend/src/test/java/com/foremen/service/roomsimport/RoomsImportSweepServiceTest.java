package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.service.image.ImageStorage.StoredObject;

/**
 * Unit tests of {@link RoomsImportSweepService}.
 * Validates: Requirements 2.4, 16.3, 17.8, 19.6, 24.7.
 */
class RoomsImportSweepServiceTest {

    private final RoomsImportJobDao dao = mock(RoomsImportJobDao.class);
    private final RoomsImportJobTerminator terminator = mock(RoomsImportJobTerminator.class);
    private final RoomsImportStorage storage = mock(RoomsImportStorage.class);
    private final RoomsImportProperties props = new RoomsImportProperties(true, null, null, null, 0, null);
    private RoomsImportSweepService sweep;

    @BeforeEach
    void setUp() {
        sweep = new RoomsImportSweepService(dao, new RoomsImportJobMapper(new ObjectMapper().findAndRegisterModules()),
                terminator, storage, props);
    }

    @Test
    void stuckJobs_areFailedWithTimeout_andOnlyWinnersCount() {
        when(dao.findStuckIds(any(), eq(RoomsImportJobDao.TERMINAL))).thenReturn(List.of(1L, 2L));
        when(terminator.fail(1L, RoomsImportErrors.FAILED_TIMEOUT)).thenReturn(1);
        when(terminator.fail(2L, RoomsImportErrors.FAILED_TIMEOUT)).thenReturn(0); // worker finished first

        assertThat(sweep.failStuckJobs()).isEqualTo(1);
    }

    @Test
    void stuckJobCutoff_usesTheConfiguredTimeout() {
        when(dao.findStuckIds(any(), any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        sweep.failStuckJobs();

        var cutoff = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dao).findStuckIds(cutoff.capture(), any());
        // default stuck timeout is 15 minutes
        assertThat(cutoff.getValue()).isBefore(before.minusMinutes(14)).isAfter(before.minusMinutes(16));
    }

    @Test
    void oneFailingJobDoesNotStopTheSweep() {
        when(dao.findStuckIds(any(), any())).thenReturn(List.of(1L, 2L));
        when(terminator.fail(1L, RoomsImportErrors.FAILED_TIMEOUT)).thenThrow(new IllegalStateException("db"));
        when(terminator.fail(2L, RoomsImportErrors.FAILED_TIMEOUT)).thenReturn(1);

        assertThat(sweep.failStuckJobs()).isEqualTo(1);
    }

    @Test
    void expiredJobs_areDeletedWithInputsAndMedia() {
        RoomsImportJobEntity job = new RoomsImportJobEntity();
        job.setId(9L);
        job.setStatus(RoomsImportStatus.COMPLETED);
        job.setInputs(List.of(Map.of("fileName", "a", "contentType", "t", "sizeBytes", 1,
                "objectKey", "rooms-import/9/in/a.txt")));
        when(dao.findExpiredIds(any(), eq(RoomsImportJobDao.TERMINAL))).thenReturn(List.of(9L));
        when(dao.findById(9L)).thenReturn(Optional.of(job));
        when(dao.deleteByJobId(9L)).thenReturn(1);

        assertThat(sweep.deleteExpiredJobs()).isEqualTo(1);

        verify(storage).deleteNow(java.util.Set.of("rooms-import/9/in/a.txt"));
        verify(storage).deleteJobPrefix(9L); // unattached media written under the prefix
    }

    @Test
    void orphanObjects_ofGoneJobs_areDeleted_butYoungOnesAndLiveJobsAreKept() {
        Instant old = Instant.now().minusSeconds(3600);
        Instant young = Instant.now().minusSeconds(5);
        when(storage.listAll()).thenReturn(List.of(
                new StoredObject("rooms-import/5/in/a.txt", old),        // job 5 gone, old   -> delete
                new StoredObject("rooms-import/5/media/b.png", null),    // job 5 gone, unknown age -> delete
                new StoredObject("rooms-import/6/in/c.txt", old),        // job 6 alive       -> keep
                new StoredObject("rooms-import/7/media/d.png", young),   // job 7 gone, young -> keep (being created)
                new StoredObject("producers/e.png", old)));              // not ours          -> keep
        when(dao.existsById(5L)).thenReturn(false);
        when(dao.existsById(6L)).thenReturn(true);
        when(dao.existsById(7L)).thenReturn(false);

        int deleted = sweep.deleteOrphanObjects();

        assertThat(deleted).isEqualTo(2);
        verify(storage).deleteNow(List.of("rooms-import/5/in/a.txt"));
        verify(storage).deleteNow(List.of("rooms-import/5/media/b.png"));
        verify(storage, never()).deleteNow(List.of("rooms-import/6/in/c.txt"));
        verify(storage, never()).deleteNow(List.of("rooms-import/7/media/d.png"));
    }

    @Test
    void sweep_runsEveryStep_evenWhenOneOfThemFails() {
        when(dao.findStuckIds(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(dao.findExpiredIds(any(), any())).thenReturn(List.of());
        when(storage.listAll()).thenReturn(List.of());

        sweep.sweep(); // must not throw

        verify(dao).findExpiredIds(any(), any());
        verify(storage).listAll();
        verify(terminator, never()).fail(anyLong(), any());
    }
}
