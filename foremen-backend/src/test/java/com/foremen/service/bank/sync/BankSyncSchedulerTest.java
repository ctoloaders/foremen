package com.foremen.service.bank.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;

/**
 * FOR-07-04a task 1.3 (R1.4) — the scheduler skips connections whose provider connector is unavailable:
 * no run is enqueued (hence no failed run, status change or notification).
 */
@ExtendWith(MockitoExtension.class)
class BankSyncSchedulerTest {

    @Mock private BankSyncOrchestrator orchestrator;
    @Mock private BankConnectionDao connectionDao;
    @Mock private BankFeedConnectorRegistry registry;

    private static BankConnectionEntity connection(long id, FeedProvider provider) {
        BankConnectionEntity c = new BankConnectionEntity();
        c.setId(id);
        c.setProvider(provider);
        return c;
    }

    @Test
    void tickSkipsConnectionsOfUnavailableProvider() {
        when(connectionDao.findByStatusInOrderByIdAsc(BankSyncScheduler.SYNCABLE))
                .thenReturn(List.of(connection(1, FeedProvider.SALT_EDGE), connection(2, FeedProvider.FINTABLE)));
        when(registry.isAvailable(FeedProvider.SALT_EDGE)).thenReturn(false);
        when(registry.isAvailable(FeedProvider.FINTABLE)).thenReturn(true);
        when(orchestrator.enqueue(2L, SyncTrigger.SCHEDULE, BankSyncOrchestrator.SYSTEM))
                .thenReturn(Optional.of(20L));

        int requested = new BankSyncScheduler(orchestrator, connectionDao, registry).tick();

        assertThat(requested).isEqualTo(1);
        verify(orchestrator).enqueue(2L, SyncTrigger.SCHEDULE, BankSyncOrchestrator.SYSTEM);
        verify(orchestrator).recoverStale();
        verifyNoMoreInteractions(orchestrator);
    }
}
