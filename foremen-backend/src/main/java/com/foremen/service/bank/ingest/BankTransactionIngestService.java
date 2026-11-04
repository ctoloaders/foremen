package com.foremen.service.bank.ingest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkStatus;
import com.foremen.service.bank.connector.NormalizedTransaction;
import com.foremen.service.bank.connector.TransactionPage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FOR-07-04 (Requirement 6) — persists connector transactions as {@code BANK_FEED} ledger lines
 * ({@code channel = BANK}, {@code origin = BANK_FEED}).
 *
 * <p>Rules:
 * <ul>
 *   <li><b>POSTED upsert</b> by {@code (account, externalId)} (R6.2): a re-delivered POSTED line
 *       updates only the mutable provider fields ({@code title}, counterparty fields,
 *       {@code bookingDate}, {@code providerCategory}, payload) and {@code lastSeenAt}; link status,
 *       links, suggestions, review and ignore state are never touched. A new line is inserted
 *       {@code UNLINKED} with {@code first_sync_run_id = runId}.</li>
 *   <li><b>PENDING → POSTED promotion</b>: a POSTED row whose {@code externalId} exists as PENDING is
 *       promoted in place (all provider fields refreshed, {@code first_sync_run_id = runId}) and is
 *       reported in {@link IngestResult#newPostedIds()} so detection runs on it.</li>
 *   <li><b>PENDING replacement</b> (R6.3): {@link #replacePending} makes the account's PENDING lines
 *       equal the supplied full pending snapshot — stale PENDING lines are deleted, the others are
 *       upserted. A pending row never demotes a POSTED line with the same id. Pending lines are never
 *       linked and never reported for detection.</li>
 *   <li><b>Skips</b> (R6.4): provider duplicates, rows repeated within one batch, zero amounts (DB CHECK
 *       {@code amount <> 0}) and malformed rows (no {@code externalId} — required by the BANK_FEED
 *       CHECK —, amount or operation date) are counted, never persisted.</li>
 *   <li><b>Normalization</b> (R6.1, R6.5, R6.6): the title is trimmed; the counterparty goes through
 *       {@link CounterpartyExtractor} (normalized IBAN + {@code counterpartyKey}); an invalid IBAN keeps
 *       its raw value in {@code counterparty_raw.ibanRaw}; amounts are scaled to 2 decimals and stored in
 *       the account currency (no conversion). A POSTED row without a booking date uses the operation
 *       date (DB CHECK: POSTED requires {@code booking_date}).</li>
 *   <li>POSTED lines are never deleted (R6.7).</li>
 * </ul>
 *
 * <p>Paging: connectors may return the pending snapshot on one page only (the stub returns it with the
 * first page), so {@link #ingest} handles the POSTED rows of a page only; the caller collects the
 * pending rows of all pages and calls {@link #replacePending} once the fetch is complete.
 * {@link #ingestAll} does both for a fully fetched page list. Detection is not run here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransactionIngestService {

    /** {@code counterparty_raw} key holding a raw IBAN that failed validation. */
    public static final String RAW_IBAN_KEY = "ibanRaw";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final BankTransactionDao transactionDao;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Ingests a fully fetched list of pages: the POSTED rows of every page, then the pending
     * replacement with the union of the pages' pending rows.
     */
    @Transactional
    public IngestResult ingestAll(BankAccountEntity account, List<TransactionPage> pages, Long runId) {
        IngestResult result = IngestResult.EMPTY;
        List<NormalizedTransaction> pending = new ArrayList<>();
        for (TransactionPage page : pages) {
            result = result.plus(ingest(account, page, runId));
            pending.addAll(page.pending());
        }
        return result.plus(replacePending(account, pending, runId));
    }

    /**
     * Ingests the POSTED rows of one page (upsert + promotion). The page's pending rows are NOT
     * processed here — pass the full pending snapshot to {@link #replacePending}. The connector's
     * {@code skippedMalformed} count is added to the result.
     */
    @Transactional
    public IngestResult ingest(BankAccountEntity account, TransactionPage page, Long runId) {
        requireBankAccount(account);
        Counters c = new Counters();
        c.skippedMalformed += Math.max(0, page.skippedMalformed());
        Map<String, NormalizedTransaction> rows = acceptable(page.posted(), c);
        if (rows.isEmpty()) {
            return c.toResult();
        }
        Map<String, BankTransactionEntity> existing = existingByExternalId(account, rows.keySet());
        OffsetDateTime now = now();
        BankSyncRunEntity run = runReference(runId);
        for (NormalizedTransaction row : rows.values()) {
            BankTransactionEntity line = existing.get(row.externalId());
            if (line == null) {
                line = newLine(account, row.externalId(), now);
                applyAll(line, account, row, BankTxnStatus.POSTED, now);
                line.setFirstSyncRun(run);
                c.newPostedIds.add(transactionDao.save(line).getId());
                c.inserted++;
            } else if (line.getStatus() == BankTxnStatus.PENDING) {
                applyAll(line, account, row, BankTxnStatus.POSTED, now);
                line.setFirstSyncRun(run);
                c.newPostedIds.add(transactionDao.save(line).getId());
                c.promoted++;
            } else {
                if (applyMutable(line, row, now)) {
                    c.updated++;
                }
                transactionDao.save(line);
            }
        }
        return c.toResult();
    }

    /**
     * Replaces the account's PENDING lines wholesale with the given full pending snapshot (R6.3):
     * PENDING lines whose id is absent are deleted, the others are inserted / refreshed. Ids already
     * POSTED on the account are ignored (no demotion).
     */
    @Transactional
    public IngestResult replacePending(BankAccountEntity account, Collection<NormalizedTransaction> pendingSnapshot,
                                       Long runId) {
        requireBankAccount(account);
        Counters c = new Counters();
        Map<String, NormalizedTransaction> rows = acceptable(pendingSnapshot == null ? List.of() : pendingSnapshot, c);
        OffsetDateTime now = now();

        Map<String, BankTransactionEntity> currentPending = transactionDao
                .findByAccountIdAndStatus(account.getId(), BankTxnStatus.PENDING).stream()
                .collect(Collectors.toMap(BankTransactionEntity::getExternalId, Function.identity(), (a, b) -> a));

        List<BankTransactionEntity> stale = currentPending.values().stream()
                .filter(line -> !rows.containsKey(line.getExternalId()))
                .toList();
        if (!stale.isEmpty()) {
            transactionDao.deleteAll(stale);
            c.pendingDeleted = stale.size();
        }

        Set<String> unknownIds = new LinkedHashSet<>(rows.keySet());
        unknownIds.removeAll(currentPending.keySet());
        Set<String> alreadyPosted = unknownIds.isEmpty() ? Set.of()
                : existingByExternalId(account, unknownIds).keySet();

        BankSyncRunEntity run = runReference(runId);
        for (NormalizedTransaction row : rows.values()) {
            BankTransactionEntity line = currentPending.get(row.externalId());
            if (line != null) {
                if (applyPendingRefresh(line, account, row, now)) {
                    c.pendingUpdated++;
                }
                transactionDao.save(line);
            } else if (!alreadyPosted.contains(row.externalId())) {
                line = newLine(account, row.externalId(), now);
                applyAll(line, account, row, BankTxnStatus.PENDING, now);
                line.setFirstSyncRun(run);
                transactionDao.save(line);
                c.pendingInserted++;
            }
        }
        return c.toResult();
    }

    // --- row filtering ---------------------------------------------------------------------------

    /** Drops duplicates / zero amounts / malformed rows, keyed by external id in delivery order. */
    private static Map<String, NormalizedTransaction> acceptable(Collection<NormalizedTransaction> rows, Counters c) {
        Map<String, NormalizedTransaction> accepted = new LinkedHashMap<>();
        for (NormalizedTransaction row : rows) {
            if (row == null) {
                c.skippedMalformed++;
            } else if (row.providerDuplicate()) {
                c.skippedDuplicate++;
            } else if (row.externalId() == null || row.externalId().isBlank()
                    || row.amount() == null || row.operationDate() == null) {
                c.skippedMalformed++;
            } else if (scale(row.amount()).signum() == 0) {
                c.skippedZeroAmount++;
            } else if (accepted.putIfAbsent(row.externalId(), row) != null) {
                c.skippedDuplicate++;
            }
        }
        return accepted;
    }

    // --- field mapping ---------------------------------------------------------------------------

    private static BankTransactionEntity newLine(BankAccountEntity account, String externalId, OffsetDateTime now) {
        BankTransactionEntity line = new BankTransactionEntity();
        line.setAccount(account);
        line.setChannel(LedgerChannel.BANK);
        line.setOrigin(LedgerOrigin.BANK_FEED);
        line.setExternalId(externalId);
        line.setLinkStatus(LinkStatus.UNLINKED);
        line.setIngestedAt(now);
        return line;
    }

    /** Writes every provider field (new line, promotion). Link / review / ignore state is untouched. */
    private static void applyAll(BankTransactionEntity line, BankAccountEntity account, NormalizedTransaction row,
                                 BankTxnStatus status, OffsetDateTime now) {
        line.setStatus(status);
        line.setAmount(scale(row.amount()));
        line.setCurrency(currency(account, row));
        line.setOperationDate(row.operationDate());
        applyMutable(line, row, now);
        line.setBookingDate(bookingDate(row, status));
    }

    /** Refreshes a still-pending line; returns whether a provider field changed. */
    private static boolean applyPendingRefresh(BankTransactionEntity line, BankAccountEntity account,
                                               NormalizedTransaction row, OffsetDateTime now) {
        BigDecimal amount = scale(row.amount());
        String currency = currency(account, row);
        boolean changed = line.getAmount() == null || line.getAmount().compareTo(amount) != 0
                || !Objects.equals(line.getCurrency(), currency)
                || !Objects.equals(line.getOperationDate(), row.operationDate());
        line.setAmount(amount);
        line.setCurrency(currency);
        line.setOperationDate(row.operationDate());
        return applyMutable(line, row, now) | changed;
    }

    /**
     * Updates the mutable provider fields (title, counterparty, booking date, category, payload) and
     * {@code lastSeenAt}; returns whether a provider field changed.
     */
    private static boolean applyMutable(BankTransactionEntity line, NormalizedTransaction row, OffsetDateTime now) {
        CounterpartyExtractor.Counterparty cp = CounterpartyExtractor.extract(row.counterpartyName(),
                row.counterpartyIbanRaw());
        String title = trimToNull(row.title());
        String name = truncate(cp.name(), 255);
        String key = truncate(cp.key(), 300);
        LocalDate bookingDate = bookingDate(row, line.getStatus());
        String category = truncate(trimToNull(row.providerCategory()), 64);
        Map<String, Object> counterpartyRaw = counterpartyRaw(row, cp);
        Map<String, Object> payload = toMap(row.payload());

        boolean changed = !Objects.equals(line.getTitle(), title)
                || !Objects.equals(line.getCounterpartyName(), name)
                || !Objects.equals(line.getCounterpartyIban(), cp.iban())
                || !Objects.equals(line.getCounterpartyKey(), key)
                || !Objects.equals(line.getBookingDate(), bookingDate)
                || !Objects.equals(line.getProviderCategory(), category)
                || !Objects.equals(line.getCounterpartyRaw(), counterpartyRaw)
                || !Objects.equals(line.getProviderPayload(), payload);

        line.setTitle(title);
        line.setCounterpartyName(name);
        line.setCounterpartyIban(cp.iban());
        line.setCounterpartyKey(key);
        line.setBookingDate(bookingDate);
        line.setProviderCategory(category);
        line.setCounterpartyRaw(counterpartyRaw);
        line.setProviderPayload(payload);
        line.setLastSeenAt(now);
        return changed;
    }

    /** POSTED requires a booking date (DB CHECK): fall back to the operation date. */
    private static LocalDate bookingDate(NormalizedTransaction row, BankTxnStatus status) {
        if (row.bookingDate() != null) {
            return row.bookingDate();
        }
        return status == BankTxnStatus.POSTED ? row.operationDate() : null;
    }

    /** R6.6: amounts are in the account currency; the row currency is a fallback only. */
    private static String currency(BankAccountEntity account, NormalizedTransaction row) {
        String accountCurrency = account.getCurrency();
        String rowCurrency = row.currency() == null ? null : row.currency().strip().toUpperCase(Locale.ROOT);
        if (accountCurrency != null && !accountCurrency.isBlank()) {
            if (rowCurrency != null && !rowCurrency.isEmpty() && !rowCurrency.equals(accountCurrency)) {
                log.warn("Bank transaction {} on account {} reported currency {} != account currency {}",
                        row.externalId(), account.getId(), rowCurrency, accountCurrency);
            }
            return accountCurrency;
        }
        return rowCurrency;
    }

    private static Map<String, Object> counterpartyRaw(NormalizedTransaction row, CounterpartyExtractor.Counterparty cp) {
        Map<String, Object> raw = toMap(row.counterpartyRaw());
        if (cp.ibanInvalid()) {
            Map<String, Object> withIban = raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
            withIban.put(RAW_IBAN_KEY, row.counterpartyIbanRaw());
            return withIban;
        }
        return raw;
    }

    /** JSON object → map; another JSON value is wrapped as {@code {"value": …}}; null / missing → null. */
    static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            return JSON.convertValue(node, MAP_TYPE);
        }
        Map<String, Object> wrapped = new HashMap<>();
        wrapped.put("value", JSON.convertValue(node, Object.class));
        return wrapped;
    }

    private static BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    // --- persistence helpers ---------------------------------------------------------------------

    private Map<String, BankTransactionEntity> existingByExternalId(BankAccountEntity account, Collection<String> ids) {
        return transactionDao.findByAccountIdAndExternalIdIn(account.getId(), ids).stream()
                .collect(Collectors.toMap(BankTransactionEntity::getExternalId, Function.identity(), (a, b) -> a));
    }

    private BankSyncRunEntity runReference(Long runId) {
        return runId == null ? null : entityManager.getReference(BankSyncRunEntity.class, runId);
    }

    private static void requireBankAccount(BankAccountEntity account) {
        if (account == null || account.getId() == null) {
            throw new IllegalArgumentException("A persisted bank account is required");
        }
        if (account.getType() != BankAccountType.BANK) {
            throw new IllegalArgumentException("Feed ingest is only for BANK accounts, got " + account.getType());
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Mutable per-call counters. */
    private static final class Counters {
        int inserted;
        int promoted;
        int updated;
        int pendingInserted;
        int pendingUpdated;
        int pendingDeleted;
        int skippedDuplicate;
        int skippedZeroAmount;
        int skippedMalformed;
        final List<Long> newPostedIds = new ArrayList<>();

        IngestResult toResult() {
            return new IngestResult(inserted, promoted, updated, pendingInserted, pendingUpdated, pendingDeleted,
                    skippedDuplicate, skippedZeroAmount, skippedMalformed, newPostedIds);
        }
    }
}
