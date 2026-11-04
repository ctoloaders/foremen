package com.foremen.service.bank.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.service.bank.connection.BankConnectionService;

/**
 * FOR-07-04 task 6.4 — {@link BankSyncScheduler} (scheduled pull + stale recovery) and
 * {@link BankConsentSweep} (consent recompute + PENDING_AUTH purge).
 */
@ExtendWith(MockitoExtension.class)
class BankSyncSchedulingTest {

    @Mock private BankSyncOrchestrator orchestrator;
    @Mock private BankConnectionDao connectionDao;
    @Mock private BankConnectionService connectionService;

    private static BankConnectionEntity connection(long id) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setId(id);
        return c;
    }

    @Test
    void tickRecoversStaleRunsThenEnqueuesScheduleForSyncableConnections() {
        when(connectionDao.findByStatusInOrderByIdAsc(BankSyncScheduler.SYNCABLE))
                .thenReturn(List.of(connection(1), connection(2)));
        when(orchestrator.enqueue(eq(1L), eq(SyncTrigger.SCHEDULE), eq(BankSyncOrchestrator.SYSTEM)))
                .thenReturn(Optional.of(10L));
        when(orchestrator.enqueue(eq(2L), eq(SyncTrigger.SCHEDULE), eq(BankSyncOrchestrator.SYSTEM)))
                .thenThrow(new IllegalStateException("boom"));

        int requested = new BankSyncScheduler(orchestrator, connectionDao).tick();

        assertThat(requested).isEqualTo(1);
        verify(orchestrator).recoverStale();
        verify(orchestrator).enqueue(2L, SyncTrigger.SCHEDULE, BankSyncOrchestrator.SYSTEM);
    }

    @Test
    void startupRunsRecovery() {
        new BankSyncScheduler(orchestrator, connectionDao).onStartup();

        verify(orchestrator).recoverOnStartup();
    }

    @Test
    void consentSweepRecomputesAndPurgesEvenWhenRecomputeFails() {
        when(connectionService.recomputeConsentStatuses()).thenThrow(new IllegalStateException("db"));
        BankConsentSweep sweep = new BankConsentSweep(connectionService, "24h");

        sweep.sweep();

        verify(connectionService).recomputeConsentStatuses();
        verify(connectionService).purgeStalePendingAuth(Duration.ofHours(24));
    }

    @Test
    void consentSweepMaxAgeFallsBackOnInvalidValue() {
        assertThat(new BankConsentSweep(connectionService, "nonsense").pendingAuthMaxAge())
                .isEqualTo(Duration.ofHours(24));
        assertThat(new BankConsentSweep(connectionService, "PT2H").pendingAuthMaxAge())
                .isEqualTo(Duration.ofHours(2));
        when(connectionService.purgeStalePendingAuth(any())).thenReturn(3);
        new BankConsentSweep(connectionService, "12h").sweep();
        verify(connectionService).purgeStalePendingAuth(Duration.ofHours(12));
    }
}
