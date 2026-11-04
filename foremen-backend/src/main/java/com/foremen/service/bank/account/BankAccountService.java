package com.foremen.service.bank.account;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankBalanceGapDao;
import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.HolderMatch;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.connector.HolderInfo;
import com.foremen.service.bank.connector.NormalizedAccount;
import com.foremen.service.bank.ingest.IbanNormalizer;
import com.foremen.service.bank.link.BankLedgerAudit;
import com.foremen.service.document.CompanyProfileUpdatedEvent;

/**
 * FOR-07-04 (Requirements 3.1–3.4, 3.7–3.10, 7.3) — {@code BANK} accounts of the connections, their
 * holder verification, the included-in-ledger switch and balance-gap acknowledgement. CASH registers
 * are managed by {@code CashLedgerService} (task 6.12).
 *
 * <h2>Upsert ({@link #upsertAccounts})</h2>
 * Called by the sync orchestrator after {@code fetchHolderInfo} + {@code fetchAccounts}:
 * <ul>
 *   <li>accounts are keyed by {@code (connection, externalId)}; mutable provider fields are updated and
 *       {@code closedAtProvider} is cleared on re-appearance;</li>
 *   <li>a new account gets {@code includedInLedger = true} only for a current / checking nature
 *       (R3.2) and never when its holder is {@code MISMATCH} (R3.8);</li>
 *   <li>accounts of the connection no longer returned are marked {@code closedAtProvider} (R3.4);</li>
 *   <li>{@link HolderMatcher} runs for every account (R3.7). The previous balance is returned so the
 *       orchestrator can run the balance continuity check (R7.1).</li>
 * </ul>
 *
 * <h2>Holder match recompute (R3.10)</h2>
 * On {@link CompanyProfileUpdatedEvent} (after commit) and via {@link #recomputeHolderMatches()}. An
 * already included account turning {@code MISMATCH} stays included; its earlier override is cleared so
 * the warning shows until a user confirms (include + reason) or excludes it.
 *
 * <h2>Toggle ({@link #setIncludedInLedger})</h2>
 * Including a {@code MISMATCH} account requires a reason ({@code 400 error.bank.holder.reasonRequired})
 * and records {@code holderOverrideBy/At/Reason} + an audit row (R3.8).
 */
@Service
public class BankAccountService {

    private static final Logger log = LoggerFactory.getLogger(BankAccountService.class);

    static final String NOT_FOUND = "error.entity.not.found";
    /** 400 when including a {@code MISMATCH} account without a reason (R3.8). */
    public static final String REASON_REQUIRED = "error.bank.holder.reasonRequired";
    /** 409 when a BANK-only action targets a CASH register. */
    public static final String NOT_BANK = "error.bank.account.notBank";

    /** Natures treated as a current / checking account (R3.2), lower case. */
    static final Set<String> CURRENT_NATURES = Set.of("account", "checking", "current");

    static final String HOLDER_NAMES_KEY = "names";
    static final String HOLDER_TAX_IDS_KEY = "taxIds";
    static final int IBAN_MAX = 34;
    static final int OVERRIDE_REASON_MAX = 2000;

    private final BankAccountDao accountDao;
    private final BankConnectionDao connectionDao;
    private final BankBalanceGapDao gapDao;
    private final CompanyProfileDao companyProfileDao;
    private final BankLedgerAudit audit;
    private final double nameSimilarity;
    private Clock clock = Clock.systemUTC();

    public BankAccountService(BankAccountDao accountDao,
                              BankConnectionDao connectionDao,
                              BankBalanceGapDao gapDao,
                              CompanyProfileDao companyProfileDao,
                              BankLedgerAudit audit,
                              @Value("${foremen.bank-ledger.holder.name-similarity:"
                                      + HolderMatcher.DEFAULT_NAME_SIMILARITY + "}") double nameSimilarity) {
        this.accountDao = accountDao;
        this.connectionDao = connectionDao;
        this.gapDao = gapDao;
        this.companyProfileDao = companyProfileDao;
        this.audit = audit;
        this.nameSimilarity = nameSimilarity > 0 && nameSimilarity <= 1
                ? nameSimilarity : HolderMatcher.DEFAULT_NAME_SIMILARITY;
    }

    /** Test hook. */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ results

    /**
     * One upserted account.
     *
     * @param accountId         the account id
     * @param externalId        the provider account id
     * @param created           {@code true} when discovered by this upsert
     * @param includedInLedger  the switch after the upsert
     * @param previousBalance   the provider balance before the upsert; nullable
     * @param previousBalanceAt when the previous balance was reported; nullable
     * @param balance           the new provider balance; nullable
     * @param balanceAt         when the new balance was reported; nullable
     */
    public record AccountUpsert(Long accountId, String externalId, boolean created, boolean includedInLedger,
                                BigDecimal previousBalance, OffsetDateTime previousBalanceAt,
                                BigDecimal balance, OffsetDateTime balanceAt) {
    }

    /**
     * Result of {@link #upsertAccounts}.
     *
     * @param accounts      the accounts returned by the provider, in input order
     * @param closedAccountIds accounts newly marked {@code closedAtProvider}
     * @param skippedMalformed provider rows without an id or currency
     */
    public record UpsertResult(List<AccountUpsert> accounts, List<Long> closedAccountIds, int skippedMalformed) {
        /** {@code accountsSeen} counter of the run. */
        public int accountsSeen() {
            return accounts.size();
        }
    }

    // ------------------------------------------------------------------ reads

    /** BANK accounts of a connection (or of all connections when {@code connectionId} is null), oldest first. */
    @Transactional(readOnly = true)
    public List<BankAccountEntity> listBankAccounts(Long connectionId) {
        List<BankAccountEntity> accounts = connectionId == null
                ? accountDao.findByTypeOrderByIdAsc(BankAccountType.BANK)
                : accountDao.findByConnectionIdOrderByIdAsc(connectionId);
        return accounts.stream().filter(a -> a.getType() == BankAccountType.BANK).toList();
    }

    @Transactional(readOnly = true)
    public BankAccountEntity get(Long id) {
        return load(id);
    }

    /** Balance gaps of an account, newest first. */
    @Transactional(readOnly = true)
    public List<BankBalanceGapEntity> listGaps(Long accountId) {
        load(accountId);
        return gapDao.findByAccountIdOrderByIdDesc(accountId);
    }

    /** {@code true} while the holder-mismatch warning is shown: {@code MISMATCH} without an override. */
    public static boolean holderWarning(BankAccountEntity account) {
        return account.getHolderMatch() == HolderMatch.MISMATCH && account.getHolderOverrideAt() == null;
    }

    // ------------------------------------------------------------------ upsert (R3.1–3.4, R3.7–3.9)

    /**
     * Upserts the provider accounts of a connection and stores the connection-level holder info.
     *
     * @param connectionId the connection
     * @param holder       connection-level holder info; {@code null} keeps the stored value
     * @param accounts     every account the provider currently returns
     */
    @Transactional
    public UpsertResult upsertAccounts(Long connectionId, HolderInfo holder, List<NormalizedAccount> accounts) {
        BankConnectionEntity connection = connectionDao.findById(connectionId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (holder != null) {
            connection.setHolderNames(holderMap(holder));
            connectionDao.save(connection);
        }
        HolderInfo connectionHolder = holderOf(connection);
        CompanyProfileEntity profile = companyProfile();

        List<BankAccountEntity> existing = accountDao.findByConnectionIdOrderByIdAsc(connectionId);
        Map<String, BankAccountEntity> byExternalId = new LinkedHashMap<>();
        for (BankAccountEntity account : existing) {
            if (account.getType() == BankAccountType.BANK && account.getExternalId() != null) {
                byExternalId.put(account.getExternalId(), account);
            }
        }

        List<AccountUpsert> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int skipped = 0;
        for (NormalizedAccount row : accounts == null ? List.<NormalizedAccount>of() : accounts) {
            String externalId = row == null ? null : trimToNull(row.externalId());
            if (externalId != null) {
                seen.add(externalId);
            }
            String currency = row == null ? null : normalizeCurrency(row.currency());
            if (externalId == null || currency == null) {
                skipped++;
                continue;
            }
            BankAccountEntity account = byExternalId.get(externalId);
            boolean created = account == null;
            BigDecimal previousBalance = created ? null : account.getBalance();
            OffsetDateTime previousBalanceAt = created ? null : account.getBalanceUpdatedAt();
            if (created) {
                account = new BankAccountEntity();
                account.setType(BankAccountType.BANK);
                account.setConnection(connection);
                account.setExternalId(externalId);
            }
            applyProviderFields(account, row, currency);
            account.setClosedAtProvider(false);
            applyHolderMatch(account, connectionHolder, profile, created);
            if (created) {
                account.setIncludedInLedger(defaultIncluded(account.getNature(), account.getHolderMatch()));
            }
            BankAccountEntity saved = accountDao.save(account);
            byExternalId.put(externalId, saved);
            results.add(new AccountUpsert(saved.getId(), externalId, created, saved.isIncludedInLedger(),
                    previousBalance, previousBalanceAt, saved.getBalance(), saved.getBalanceUpdatedAt()));
        }
        if (skipped > 0) {
            log.warn("Bank connection {}: skipped {} malformed provider accounts", connectionId, skipped);
        }

        List<Long> closed = new ArrayList<>();
        for (BankAccountEntity account : existing) {
            if (account.getType() == BankAccountType.BANK && account.getExternalId() != null
                    && !seen.contains(account.getExternalId()) && !account.isClosedAtProvider()) {
                account.setClosedAtProvider(true);
                accountDao.save(account);
                closed.add(account.getId());
            }
        }
        return new UpsertResult(results, closed, skipped);
    }

    /** R3.2 + R3.8: current / checking nature ⇒ included, never when the holder is {@code MISMATCH}. */
    static boolean defaultIncluded(String nature, HolderMatch holderMatch) {
        if (holderMatch == HolderMatch.MISMATCH) {
            return false;
        }
        return nature != null && CURRENT_NATURES.contains(nature.trim().toLowerCase(Locale.ROOT));
    }

    private void applyProviderFields(BankAccountEntity account, NormalizedAccount row, String currency) {
        if (trimToNull(row.name()) != null) {
            account.setName(truncate(row.name().trim(), 255));
        }
        account.setNature(truncate(trimToNull(row.nature()), 64));
        String iban = IbanNormalizer.clean(row.iban());
        account.setIban(iban != null && iban.length() <= IBAN_MAX ? iban : null);
        account.setAccountNumber(truncate(trimToNull(row.accountNumber()), 64));
        account.setCurrency(currency);
        account.setBalance(scale(row.balance()));
        account.setAvailableBalance(scale(row.availableBalance()));
        if (row.balance() != null || row.availableBalance() != null) {
            Instant at = row.balanceAt() != null ? row.balanceAt() : clock.instant();
            account.setBalanceUpdatedAt(at.atOffset(ZoneOffset.UTC));
        }
        account.setHolderName(truncate(trimToNull(row.holderName()), 255));
    }

    // ------------------------------------------------------------------ holder match (R3.7–3.10)

    /** Recomputes the holder match of every BANK account against the current company profile. */
    @Transactional
    public int recomputeHolderMatches() {
        CompanyProfileEntity profile = companyProfile();
        Map<Long, HolderInfo> holderByConnection = new LinkedHashMap<>();
        int changed = 0;
        for (BankAccountEntity account : accountDao.findByTypeOrderByIdAsc(BankAccountType.BANK)) {
            BankConnectionEntity connection = account.getConnection();
            HolderInfo connectionHolder = connection == null ? null
                    : holderByConnection.computeIfAbsent(connection.getId(), id -> holderOf(connection));
            HolderMatch before = account.getHolderMatch();
            applyHolderMatch(account, connectionHolder, profile, false);
            if (before != account.getHolderMatch()) {
                accountDao.save(account);
                changed++;
            }
        }
        return changed;
    }

    /** R3.10: recompute after a company profile save has committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompanyProfileUpdated(CompanyProfileUpdatedEvent event) {
        int changed = recomputeHolderMatches();
        log.info("Company profile {} updated: holder match changed on {} bank accounts",
                event == null ? null : event.profileId(), changed);
    }

    private void applyHolderMatch(BankAccountEntity account, HolderInfo connectionHolder,
                                  CompanyProfileEntity profile, boolean created) {
        HolderMatch next = HolderMatcher.match(
                connectionHolder == null ? List.of() : connectionHolder.names(),
                connectionHolder == null ? List.of() : connectionHolder.taxIds(),
                account.getHolderName(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getNip(),
                nameSimilarity);
        HolderMatch previous = account.getHolderMatch();
        account.setHolderMatch(next);
        if (!created && next == HolderMatch.MISMATCH && previous != HolderMatch.MISMATCH) {
            // R3.10: stays included, but the warning must show again until a user confirms / excludes.
            account.setHolderOverrideBy(null);
            account.setHolderOverrideAt(null);
            account.setHolderOverrideReason(null);
        }
    }

    // ------------------------------------------------------------------ toggle (R3.2, R3.3, R3.8)

    /**
     * Switches a BANK account in / out of the ledger. Existing transactions are kept either way (R3.3).
     *
     * @param reason required when including a {@code MISMATCH} account (also to confirm one that is
     *               already included while the warning shows)
     */
    @Transactional
    public BankAccountEntity setIncludedInLedger(Long accountId, boolean included, String reason) {
        BankAccountEntity account = load(accountId);
        if (account.getType() != BankAccountType.BANK) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_BANK);
        }
        Map<String, Object> before = snapshot(account);
        if (included && account.getHolderMatch() == HolderMatch.MISMATCH) {
            String why = trimToNull(reason);
            if (why == null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, REASON_REQUIRED);
            }
            account.setHolderOverrideBy(BankLedgerAudit.currentUser());
            account.setHolderOverrideAt(OffsetDateTime.now(clock));
            account.setHolderOverrideReason(truncate(why, OVERRIDE_REASON_MAX));
            account.setIncludedInLedger(true);
            BankAccountEntity saved = accountDao.save(account);
            audit.write(BankAccountEntity.class, saved.getId(), "HOLDER_OVERRIDE", before, snapshot(saved));
            return saved;
        }
        if (account.isIncludedInLedger() == included) {
            return account;
        }
        account.setIncludedInLedger(included);
        BankAccountEntity saved = accountDao.save(account);
        audit.write(BankAccountEntity.class, saved.getId(), "UPDATE", before, snapshot(saved));
        return saved;
    }

    // ------------------------------------------------------------------ balance gaps (R7.3)

    /**
     * Acknowledges a balance gap with an optional note; clears the account's {@code hasBalanceGap}
     * when no unacknowledged gap remains. Idempotent for an already acknowledged gap.
     */
    @Transactional
    public BankBalanceGapEntity acknowledgeGap(Long gapId, String note) {
        BankBalanceGapEntity gap = gapDao.findById(gapId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (gap.getAcknowledgedAt() != null) {
            return gap;
        }
        Map<String, Object> before = gapSnapshot(gap);
        gap.setAcknowledgedAt(OffsetDateTime.now(clock));
        gap.setAcknowledgedBy(BankLedgerAudit.currentUser());
        gap.setNote(trimToNull(note));
        BankBalanceGapEntity saved = gapDao.save(gap);
        BankAccountEntity account = saved.getAccount();
        if (account != null) {
            boolean open = gapDao.findByAccountIdAndAcknowledgedAtIsNull(account.getId()).stream()
                    .anyMatch(g -> !Objects.equals(g.getId(), saved.getId()));
            if (!open && account.isHasBalanceGap()) {
                account.setHasBalanceGap(false);
                accountDao.save(account);
            }
        }
        audit.write(BankBalanceGapEntity.class, saved.getId(), "ACKNOWLEDGE", before, gapSnapshot(saved));
        return saved;
    }

    // ------------------------------------------------------------------ helpers

    private BankAccountEntity load(Long id) {
        if (id == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        return accountDao.findById(id).orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private CompanyProfileEntity companyProfile() {
        Iterator<CompanyProfileEntity> it = companyProfileDao.findAll().iterator();
        return it.hasNext() ? it.next() : null;
    }

    /** {@code {"names": [...], "taxIds": [...]}} — the JSONB shape of {@code bank_connections.holder_names}. */
    static Map<String, Object> holderMap(HolderInfo holder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(HOLDER_NAMES_KEY, new ArrayList<>(holder.names()));
        m.put(HOLDER_TAX_IDS_KEY, new ArrayList<>(holder.taxIds()));
        return m;
    }

    /** The connection-level holder info stored as JSONB; empty when none. */
    static HolderInfo holderOf(BankConnectionEntity connection) {
        Map<String, Object> m = connection == null ? null : connection.getHolderNames();
        if (m == null) {
            return new HolderInfo(List.of(), List.of());
        }
        return new HolderInfo(strings(m.get(HOLDER_NAMES_KEY)), strings(m.get(HOLDER_TAX_IDS_KEY)));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> c)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : c) {
            if (o != null && !o.toString().isBlank()) {
                out.add(o.toString());
            }
        }
        return out;
    }

    private static String normalizeCurrency(String raw) {
        String value = trimToNull(raw);
        if (value == null || value.length() != 3) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** Audited account fields (IBAN masked to the last 4, R24.1). */
    static Map<String, Object> snapshot(BankAccountEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("connectionId", a.getConnection() == null ? null : a.getConnection().getId());
        m.put("name", a.getName());
        m.put("ibanLast4", a.getIban() == null || a.getIban().length() < 4 ? null
                : a.getIban().substring(a.getIban().length() - 4));
        m.put("includedInLedger", a.isIncludedInLedger());
        m.put("holderMatch", a.getHolderMatch() == null ? null : a.getHolderMatch().name());
        m.put("holderOverrideBy", a.getHolderOverrideBy());
        m.put("holderOverrideReason", a.getHolderOverrideReason());
        return m;
    }

    private static Map<String, Object> gapSnapshot(BankBalanceGapEntity g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("accountId", g.getAccount() == null ? null : g.getAccount().getId());
        m.put("difference", g.getDifference() == null ? null : g.getDifference().toPlainString());
        m.put("acknowledgedAt", g.getAcknowledgedAt() == null ? null : g.getAcknowledgedAt().toString());
        m.put("acknowledgedBy", g.getAcknowledgedBy());
        m.put("note", g.getNote());
        return m;
    }
}
