package com.foremen.service.bank.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankBalanceGapDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankSyncRunAccountDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.service.bank.ingest.BalanceContinuityChecker;
import com.foremen.service.bank.notify.BalanceGapEvent;
import com.foremen.service.bank.notify.BankSyncFinishedEvent;
import com.foremen.service.bank.notify.SyncFailedEvent;
import com.foremen.service.bank.sync.SyncRunRecorder.RunOutcome;

/** FOR-07-04 task 6.4 — {@link SyncRunRecorder}: run transitions, events, stale recovery, gaps. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncRunRecorderTest {

    private static final Instant NOW = Instant.parse("2026-01-15T06:30:00Z");

    @Mock private BankSyncRunDao runDao;
    @Mock private BankSyncRunAccountDao runAccountDao;
    @Mock private BankConnectionDao connectionDao;
    @Mock private BankAccountDao accountDao;
    @Mock private BankTransactionDao transactionDao;
    @Mock private BankBalanceGapDao gapDao;
    @Mock private ApplicationEventPublisher events;

    private SyncRunRecorder recorder;
    private BankConnectionEntity connection;

    @BeforeEach
    void setUp() {
        recorder = new SyncRunRecorder(runDao, runAccountDao, connectionDao, accountDao, transactionDao, gapDao, events);
        recorder.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        connection = new BankConnectionEntity();
        connection.setId(7L);
        when(connectionDao.findById(7L)).thenReturn(Optional.of(connection));
        when(runDao.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private BankSyncRunEntity run(long id, SyncRunStatus status) {
        BankSyncRunEntity r = new BankSyncRunEntity();
        r.setId(id);
        r.setConnection(connection);
        r.setTrigger(SyncTrigger.MANUAL);
        r.setStatus(status);
        when(runDao.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private static RunOutcome outcome(SyncRunStatus status, int toReview) {
        return new RunOutcome(status, 2, 3, 1, 0, 0, toReview, null, null, null);
    }

    @Test
    void insertQueuedCreatesAQueuedRun() {
        when(runDao.save(any())).thenAnswer(inv -> {
            BankSyncRunEntity r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        assertThat(recorder.insertQueued(7L, SyncTrigger.CONNECT, "alice")).contains(100L);

        ArgumentCaptor<BankSyncRunEntity> saved = ArgumentCaptor.forClass(BankSyncRunEntity.class);
        verify(runDao).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SyncRunStatus.QUEUED);
        assertThat(saved.getValue().getTrigger()).isEqualTo(SyncTrigger.CONNECT);
        assertThat(saved.getValue().getRequestedBy()).isEqualTo("alice");
        assertThat(recorder.insertQueued(8L, SyncTrigger.CONNECT, "alice")).isEmpty();
    }

    @Test
    void markRunningOnlyFromQueued() {
        BankSyncRunEntity r = run(100L, SyncRunStatus.QUEUED);
        run(101L, SyncRunStatus.RUNNING);

        assertThat(recorder.markRunning(100L)).isPresent();
        assertThat(r.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(r.getStartedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(recorder.markRunning(101L)).isEmpty();
    }

    @Test
    void finishStoresCountersStampsTheConnectionAndPublishesFinished() {
        BankSyncRunEntity r = run(100L, SyncRunStatus.RUNNING);

        assertThat(recorder.finish(100L, outcome(SyncRunStatus.SUCCEEDED, 2))).isTrue();

        assertThat(r.getStatus()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(r.getTransactionsNew()).isEqualTo(3);
        assertThat(r.getToReview()).isEqualTo(2);
        assertThat(connection.getLastSyncAt()).isNotNull();
        assertThat(connection.getLastSuccessfulSyncAt()).isNotNull();
        verify(events).publishEvent(new BankSyncFinishedEvent(100L, 7L, 2));
        verify(events, never()).publishEvent(any(SyncFailedEvent.class));
    }

    @Test
    void failedFinishPublishesSyncFailedAndKeepsLastSuccessful() {
        run(100L, SyncRunStatus.RUNNING);

        recorder.finish(100L, new RunOutcome(SyncRunStatus.FAILED, 0, 0, 0, 0, 0, 0, "REAUTH",
                "AispConsentExpired", "expired"));

        assertThat(connection.getLastSuccessfulSyncAt()).isNull();
        verify(events).publishEvent(new SyncFailedEvent(7L, 100L));
    }

    @Test
    void finishIgnoresARunThatIsNoLongerRunning() {
        BankSyncRunEntity r = run(100L, SyncRunStatus.FAILED);

        assertThat(recorder.finish(100L, outcome(SyncRunStatus.SUCCEEDED, 0))).isFalse();
        assertThat(r.getStatus()).isEqualTo(SyncRunStatus.FAILED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void failStaleRecoversOldRunningAndQueuedRunsOnly() {
        Instant cutoff = NOW.minusSeconds(30 * 60);
        BankSyncRunEntity oldRunning = run(1L, SyncRunStatus.RUNNING);
        oldRunning.setStartedAt(OffsetDateTime.ofInstant(cutoff.minusSeconds(60), ZoneOffset.UTC));
        BankSyncRunEntity freshRunning = run(2L, SyncRunStatus.RUNNING);
        freshRunning.setStartedAt(OffsetDateTime.ofInstant(cutoff.plusSeconds(60), ZoneOffset.UTC));
        BankSyncRunEntity oldQueued = run(3L, SyncRunStatus.QUEUED);
        oldQueued.setCreatedDate(LocalDateTime.ofInstant(cutoff.minusSeconds(60), ZoneId.systemDefault()));
        when(runDao.findByStatusInOrderByIdAsc(List.of(SyncRunStatus.QUEUED, SyncRunStatus.RUNNING)))
                .thenReturn(List.of(oldRunning, freshRunning, oldQueued));

        assertThat(recorder.failStale(cutoff)).isEqualTo(2);

        assertThat(oldRunning.getStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(oldRunning.getErrorCode()).isEqualTo(SyncRunRecorder.STALE);
        assertThat(freshRunning.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(oldQueued.getStatus()).isEqualTo(SyncRunStatus.FAILED);
    }

    @Test
    void recordGapFlagsTheAccountAndPublishesTheEvent() {
        BankAccountEntity account = new BankAccountEntity();
        account.setId(5L);
        when(accountDao.findById(5L)).thenReturn(Optional.of(account));
        when(gapDao.save(any())).thenAnswer(inv -> {
            BankBalanceGapEntity g = inv.getArgument(0);
            g.setId(9L);
            return g;
        });
        BalanceContinuityChecker.Gap gap = new BalanceContinuityChecker.Gap(new BigDecimal("120.00"),
                new BigDecimal("150.00"), new BigDecimal("30.00"));

        assertThat(recorder.recordGap(5L, gap, OffsetDateTime.now(), OffsetDateTime.now())).contains(9L);

        assertThat(account.isHasBalanceGap()).isTrue();
        verify(events).publishEvent(new BalanceGapEvent(5L, 9L));
    }
}
