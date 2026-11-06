package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.foremen.dao.RoomsImportJobDao;

/** Unit tests of {@link RoomsImportCancellation}. Validates: Requirement 24.3. */
class RoomsImportCancellationTest {

    private final RoomsImportJobDao dao = mock(RoomsImportJobDao.class);

    @Test
    void goneJob_isCancelled() {
        when(dao.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> new RoomsImportCancellation(dao, Duration.ZERO).assertActive(1L))
                .isInstanceOf(JobCancelledException.class);
    }

    @Test
    void activeJob_passes_andIsCachedWithinTheWindow() {
        when(dao.existsById(1L)).thenReturn(true);
        RoomsImportCancellation c = new RoomsImportCancellation(dao, Duration.ofMinutes(1));

        assertThatCode(() -> {
            c.assertActive(1L);
            c.assertActive(1L);
            c.assertActive(1L);
        }).doesNotThrowAnyException();

        verify(dao, times(1)).existsById(1L);
    }

    @Test
    void zeroWindow_checksEveryTime_andNoticesCancellationImmediately() {
        when(dao.existsById(1L)).thenReturn(true, false);
        RoomsImportCancellation c = new RoomsImportCancellation(dao, Duration.ZERO);

        c.assertActive(1L);

        assertThatThrownBy(() -> c.assertActive(1L)).isInstanceOf(JobCancelledException.class);
    }
}
