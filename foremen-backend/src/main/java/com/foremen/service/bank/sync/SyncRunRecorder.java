package com.foremen.service.bank.sync;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankBalanceGapDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.BankSyncRunAccountDao;
import com.foremen.dao.BankSyncRunDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankSyncRunAccountEntity;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.dao.model.SyncTrigger;
import com.foremen.service.bank.ingest.BalanceContinuityChecker;
import com.foremen.service.bank.ingest.BalanceContinuityChecker.PostedAmount;
import com.foremen.service.bank.ingest.IngestResult;
import com.foremen.service.bank.notify.BalanceGapEvent;
import com.foremen.service.bank.notify.BankSyncFinishedEvent;
import com.foremen.service.bank.notify.SyncFailedEvent;

/**
 * FOR-07-04 (Requirements 5.2, 5.3, 5.6, 7.2) — the short transactions of a sync run, kept in a separate
 * bean so every step of {@link BankSyncOrchestrator#run(Long)} commits on its own (and so the
 * {@code QUEUED} insert can run in {@code REQUIRES_NEW}, isolated from the caller's transaction).
 */
@Component
public class SyncRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(SyncRunRecorder.class);

    /** Error code of a run recovered as stale (R5.6). */
    public static final String STALE = "error.bank.sync.stale";

    static final int ERROR_CLASS_MAX = 32;
    static final int ERROR_CODE_MAX = 64;
    private static final List<SyncRunStatus> ACTIVE = List.of(SyncRunStatus.QUEUED, SyncRunStatus.RUNNING);

    private final BankSyncRunDao runDao;
    private final BankSyncRunAccountDao runAccountDao;
    private final BankConnectionDao connectionDao;
    private final BankAccountDao accountDao;
    private final BankTransactionDao transactionDao;
    private final BankBalanceGapDao gapDao;
    private final ApplicationEventPublisher events;
    private Clock clock = Clock.systemUTC();

    public SyncRunRecorder(BankSyncRunDao runDao, BankSyncRunAccountDao runAccountDao,
                           BankConnectionDao connectionDao, BankAccountDao accountDao,
                           BankTransactionDao transactionDao, BankBalanceGapDao gapDao,
                           ApplicationEventPublisher events) {
        this.runDao = runDao;
        this.runAccountDao = runAccountDao;
        this.connectionDao = connectionDao;
        this.accountDao = accountDao;
        this.transactionDao = transactionDao;
        this.gapDao = gapDao;
        this.events = events;
    }

    /** Test hook. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ records

    /** The active run of a connection, with its staleness at the given cutoff. */
    public record ActiveRun(Long runId, SyncRunStatus status, boolean stale) {
    }

    /** A run that has just been moved to {@code RUNNING}. */
    public record RunStart(Long runId, Long connectionId, SyncTrigger trigger) {
    }

    /**
     * The final state of a run.
     *
     * @param errorClass   connector error class ({@code CONFIG}, {@code REAUTH}, …) or {@code null}
     * @param errorCode    raw provider error code, else the message key; {@code null} on success
     * @param errorMessage raw message for support (never shown to users); nullable
     */
    public record RunOutcome(SyncRunStatus status, int accountsSeen, int transactionsNew, int transactionsUpdated,
                             int pendingReplaced, int rowsSkipped, int toReview,
                             String errorClass, String errorCode, String errorMessage) {
    }

    // ------------------------------------------------------------------ enqueue (R5.3)

    @Transactional(readOnly = true)
    public Optional<ActiveRun> findActive(Long connectionId, Instant staleCutoff) {
        return runDao.findActiveByConnectionId(connectionId)
                .map(run -> new ActiveRun(run.getId(), run.getStatus(), isStale(run, staleCutoff)));
    }

    /**
     * Inserts a {@code QUEUED} run in its own transaction. A second active run of the same connection
     * violates the partial UNIQUE index and surfaces as a
     * {@link org.springframework.dao.DataIntegrityViolationException} (the caller coalesces).
     *
     * @return the new run id; empty when the connection does not exist
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> insertQueued(Long connectionId, SyncTrigger trigger, String requestedBy) {
        Optional<BankConnectionEntity> connection = connectionDao.findById(connectionId);
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        BankSyncRunEntity run = new BankSyncRunEntity();
        run.setConnection(connection.get());
        run.setTrigger(trigger);
        run.setStatus(SyncRunStatus.QUEUED);
        run.setRequestedBy(truncate(requestedBy, 255));
        return Optional.of(runDao.save(run).getId());
    }

    /** Fails an active run (stale / rejected by the executor) in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failActive(Long runId, String errorCode, String message) {
        BankSyncRunEntity run = runDao.findById(runId).orElse(null);
        if (run == null || !run.getStatus().isActive()) {
            return false;
        }
        fail(run, errorCode, message);
        return true;
    }

    // ------------------------------------------------------------------ run steps

    /** {@code QUEUED → RUNNING}; empty when the run is unknown or no longer queued. */
    @Transactional
    public Optional<RunStart> markRunning(Long runId) {
        BankSyncRunEntity run = runDao.findById(runId).orElse(null);
        if (run == null || run.getStatus() != SyncRunStatus.QUEUED) {
            return Optional.empty();
        }
        run.setStatus(SyncRunStatus.RUNNING);
        run.setStartedAt(now());
        BankSyncRunEntity saved = runDao.save(run);
        return Optional.of(new RunStart(saved.getId(), saved.getConnection().getId(), saved.getTrigger()));
    }

    /** One {@code bank_sync_run_accounts} row. */
    @Transactional
    public void recordAccount(Long runId, Long accountId, IngestResult result, SyncRunStatus status,
                              String errorCode) {
        BankSyncRunEntity run = runDao.findById(runId).orElse(null);
        BankAccountEntity account = accountDao.findById(accountId).orElse(null);
        if (run == null || account == null) {
            return;
        }
        BankSyncRunAccountEntity row = runAccountDao.findByRunIdAndAccountId(runId, accountId)
                .orElseGet(BankSyncRunAccountEntity::new);
        row.setRun(run);
        row.setAccount(account);
        IngestResult r = result == null ? IngestResult.EMPTY : result;
        row.setTransactionsNew(r.transactionsNew());
        row.setTransactionsUpdated(r.transactionsUpdated());
        row.setPendingReplaced(r.pendingReplaced());
        row.setStatus(status);
        row.setErrorCode(truncate(errorCode, ERROR_CODE_MAX));
        runAccountDao.save(row);
    }

    /** Stores the account's resume cursor ({@code bank_accounts.transactions_cursor}). */
    @Transactional
    public void storeCursor(Long accountId, String cursor) {
        accountDao.findById(accountId).ifPresent(account -> {
            if (!Objects.equals(account.getTransactionsCursor(), cursor)) {
                account.setTransactionsCursor(cursor);
                accountDao.save(account);
            }
        });
    }

    /** POSTED amounts of an account booked within {@code [from, to]} (R7.1). */
    @Transactional(readOnly = true)
    public List<PostedAmount> postedBetween(Long accountId, LocalDate from, LocalDate to) {
        List<PostedAmount> amounts = new ArrayList<>();
        transactionDao.findByAccountIdAndStatusAndBookingDateBetween(accountId, BankTxnStatus.POSTED, from, to)
                .forEach(t -> amounts.add(new PostedAmount(t.getBookingDate(), t.getAmount())));
        return amounts;
    }

    /**
     * Records a {@code Balance_Gap}, flags the account and publishes {@link BalanceGapEvent} (R7.2).
     *
     * @return the gap id; empty when the account is gone
     */
    @Transactional
    public Optional<Long> recordGap(Long accountId, BalanceContinuityChecker.Gap gap,
                                    OffsetDateTime periodFrom, OffsetDateTime periodTo) {
        BankAccountEntity account = accountDao.findById(accountId).orElse(null);
        if (account == null) {
            return Optional.empty();
        }
        BankBalanceGapEntity row = new BankBalanceGapEntity();
        row.setAccount(account);
        row.setPeriodFrom(periodFrom);
        row.setPeriodTo(periodTo);
        row.setExpected(gap.expected());
        row.setActual(gap.actual());
        row.setDifference(gap.difference());
        Long gapId = gapDao.save(row).getId();
        if (!account.isHasBalanceGap()) {
            account.setHasBalanceGap(true);
            accountDao.save(account);
        }
        events.publishEvent(new BalanceGapEvent(accountId, gapId));
        return Optional.of(gapId);
    }

    /**
     * Finishes a {@code RUNNING} run: status, counters, error, the connection's sync timestamps, and
     * the {@link BankSyncFinishedEvent} (+ {@link SyncFailedEvent} when {@code FAILED}). A run that is no
     * longer {@code RUNNING} (recovered as stale meanwhile) is left untouched.
     *
     * @return {@code true} when the run was finished by this call
     */
    @Transactional
    public boolean finish(Long runId, RunOutcome outcome) {
        BankSyncRunEntity run = runDao.findById(runId).orElse(null);
        if (run == null || run.getStatus() != SyncRunStatus.RUNNING) {
            log.warn("Bank sync run {} is no longer RUNNING; outcome {} not recorded", runId,
                    outcome.status());
            return false;
        }
        OffsetDateTime now = now();
        run.setStatus(outcome.status());
        run.setFinishedAt(now);
        run.setAccountsSeen(outcome.accountsSeen());
        run.setTransactionsNew(outcome.transactionsNew());
        run.setTransactionsUpdated(outcome.transactionsUpdated());
        run.setPendingReplaced(outcome.pendingReplaced());
        run.setRowsSkipped(outcome.rowsSkipped());
        run.setToReview(outcome.toReview());
        run.setErrorClass(truncate(outcome.errorClass(), ERROR_CLASS_MAX));
        run.setErrorCode(truncate(outcome.errorCode(), ERROR_CODE_MAX));
        run.setErrorMessage(outcome.errorMessage());
        runDao.save(run);

        Long connectionId = run.getConnection().getId();
        connectionDao.findById(connectionId).ifPresent(connection -> {
            connection.setLastSyncAt(now);
            if (outcome.status() != SyncRunStatus.FAILED) {
                connection.setLastSuccessfulSyncAt(now);
            }
            connectionDao.save(connection);
        });
        events.publishEvent(new BankSyncFinishedEvent(runId, connectionId, outcome.toReview()));
        if (outcome.status() == SyncRunStatus.FAILED) {
            events.publishEvent(new SyncFailedEvent(connectionId, runId));
        }
        return true;
    }

    // ------------------------------------------------------------------ recovery (R5.6)

    /**
     * Marks active runs older than the cutoff {@code FAILED} ({@value #STALE}): {@code RUNNING} by
     * {@code started_at}, {@code QUEUED} (never picked up) by {@code created_date}.
     *
     * @return the number of recovered runs
     */
    @Transactional
    public int failStale(Instant cutoff) {
        int recovered = 0;
        for (BankSyncRunEntity run : runDao.findByStatusInOrderByIdAsc(ACTIVE)) {
            if (isStale(run, cutoff)) {
                fail(run, STALE, "Run left " + run.getStatus() + " past the stale-after window");
                recovered++;
            }
        }
        return recovered;
    }

    /** Ids of {@code QUEUED} runs, oldest first. */
    @Transactional(readOnly = true)
    public List<Long> queuedRunIds() {
        return runDao.findByStatusInOrderByIdAsc(List.of(SyncRunStatus.QUEUED)).stream()
                .map(BankSyncRunEntity::getId)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private void fail(BankSyncRunEntity run, String errorCode, String message) {
        run.setStatus(SyncRunStatus.FAILED);
        run.setFinishedAt(now());
        run.setErrorCode(truncate(errorCode, ERROR_CODE_MAX));
        run.setErrorMessage(message);
        runDao.save(run);
        events.publishEvent(new SyncFailedEvent(run.getConnection().getId(), run.getId()));
    }

    static boolean isStale(BankSyncRunEntity run, Instant cutoff) {
        if (cutoff == null || run.getStatus() == null || !run.getStatus().isActive()) {
            return false;
        }
        if (run.getStatus() == SyncRunStatus.RUNNING && run.getStartedAt() != null) {
            return run.getStartedAt().toInstant().isBefore(cutoff);
        }
        LocalDateTime created = run.getCreatedDate();
        // created_date is written by Spring Data auditing with LocalDateTime.now() (system zone).
        return created != null && created.atZone(ZoneId.systemDefault()).toInstant().isBefore(cutoff);
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
