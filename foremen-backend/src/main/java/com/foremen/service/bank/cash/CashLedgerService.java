package com.foremen.service.bank.cash;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.HolderMatch;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.SystemSource;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.BankLedgerAudit;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkOutcome;
import com.foremen.service.bank.link.LinkRequest;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-04 (Requirements 27.1–27.7, 28.1; design §Cash ledger) — cash accounts and cash ledger entries.
 *
 * <ul>
 *   <li><b>Cash accounts</b> ({@code bank_accounts.type = CASH}): create / edit / archive; at most one
 *       {@code defaultForCash} per currency (setting it clears the others, archiving clears it);
 *       balance = {@code openingBalance + Σ entry amounts}.</li>
 *   <li><b>Manual entries</b> ({@code channel = CASH}, {@code origin = MANUAL}, {@code POSTED}): created
 *       with an optional link through {@link BankLinkService#createLink} in the same transaction; edited /
 *       deleted only while none of their links is locked. Deleting removes the (unlocked) links; payouts
 *       keep their status.</li>
 *   <li><b>SYSTEM entries</b> ({@link #recordSystemEntry}): written by the payroll / procurement hooks,
 *       at most one per ({@code system_source}, {@code system_source_id}) (Property 11, backed by the
 *       partial UNIQUE index); read-only in the ledger.</li>
 * </ul>
 *
 * <p>Cash entries never take part in the balance-continuity check, connector sync or auto-detection
 * (R27.7): those only read {@code BANK} accounts / {@code BANK_FEED} lines.
 */
@Service
public class CashLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CashLedgerService.class);

    static final String NOT_FOUND = "error.entity.not.found";
    /** 409 — the entry has a locked link (R27.6). */
    public static final String ENTRY_NOT_EDITABLE = "error.bank.entry.notEditable";
    /** 409 — SYSTEM entries are corrected at their source (R27.6). */
    public static final String ENTRY_SYSTEM_READ_ONLY = "error.bank.entry.systemReadOnly";
    /** 400 — only cash ledger lines can be edited / deleted here. */
    public static final String ENTRY_NOT_CASH = "error.bank.entry.notCash";
    /** 400 — the cash amount is missing (e.g. no document total yet, R28.1). */
    public static final String AMOUNT_REQUIRED = "error.bank.cash.amountRequired";
    /** 400 — the cash amount must be positive. */
    public static final String AMOUNT_INVALID = "error.bank.cash.amountInvalid";
    /** 400 — the entry date is required. */
    public static final String DATE_REQUIRED = "error.bank.cash.dateRequired";
    /** 400 — the entry direction is required. */
    public static final String DIRECTION_REQUIRED = "error.bank.cash.directionRequired";
    /** 409 — archived registers accept no new entries / cannot be the default. */
    public static final String ACCOUNT_ARCHIVED = "error.bank.cash.accountArchived";
    /** 400 — no cash account was given and no default one exists. */
    public static final String ACCOUNT_REQUIRED = "error.bank.cash.accountRequired";
    /** 400 — the account is not a cash account. */
    public static final String NOT_CASH_ACCOUNT = "error.bank.cash.notCashAccount";
    /** 400 — the cash account name is required. */
    public static final String NAME_REQUIRED = "error.bank.cash.nameRequired";
    /** 400 — the currency is not a 3-letter ISO code. */
    public static final String CURRENCY_INVALID = "error.bank.cash.currencyInvalid";
    /** 409 — the currency of a cash account with entries cannot change. */
    public static final String CURRENCY_LOCKED = "error.bank.cash.currencyLocked";
    /** 409 — the source (payout / document) already has its SYSTEM cash entry (Property 11). */
    public static final String ALREADY_RECORDED = "error.bank.cash.alreadyRecorded";

    /** Payroll and procurement are PLN-only; the default currency for a manual entry without an account. */
    public static final String DEFAULT_CURRENCY = "PLN";

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int NAME_MAX = 255;

    private final BankAccountDao accountDao;
    private final BankTransactionDao transactionDao;
    private final BankTransactionLinkDao linkDao;
    private final BankLinkService linkService;
    private final BankLedgerAudit audit;
    private final EntityManager entityManager;

    public CashLedgerService(BankAccountDao accountDao, BankTransactionDao transactionDao,
                             BankTransactionLinkDao linkDao, BankLinkService linkService, BankLedgerAudit audit,
                             EntityManager entityManager) {
        this.accountDao = accountDao;
        this.transactionDao = transactionDao;
        this.linkDao = linkDao;
        this.linkService = linkService;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ============================================================================================
    // Cash accounts (R27.1)
    // ============================================================================================

    /** All cash accounts (archived included), oldest first, with computed balances. */
    @Transactional(readOnly = true)
    public List<CashAccountDto> listAccounts() {
        List<BankAccountEntity> accounts = accountDao.findByTypeOrderByIdAsc(BankAccountType.CASH);
        Map<Long, BigDecimal> sums = entrySums(accounts.stream().map(BankAccountEntity::getId).toList());
        return accounts.stream().map(a -> toDto(a, sums.getOrDefault(a.getId(), BigDecimal.ZERO))).toList();
    }

    /** One cash account with its computed balance. */
    @Transactional(readOnly = true)
    public CashAccountDto getAccount(Long id) {
        BankAccountEntity account = findCashAccount(id);
        return toDto(account, entrySums(List.of(id)).getOrDefault(id, BigDecimal.ZERO));
    }

    /** The computed balances of the given cash accounts ({@code openingBalance + Σ amounts}). */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> balances(List<BankAccountEntity> cashAccounts) {
        Map<Long, BigDecimal> sums = entrySums(cashAccounts.stream().map(BankAccountEntity::getId).toList());
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (BankAccountEntity a : cashAccounts) {
            out.put(a.getId(), scale(nz(a.getOpeningBalance()).add(sums.getOrDefault(a.getId(), BigDecimal.ZERO))));
        }
        return out;
    }

    /** Creates a cash account; {@code defaultForCash} clears the flag on the other accounts of the currency. */
    @Transactional
    public CashAccountDto createAccount(CashAccountRequest request) {
        if (request == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, NAME_REQUIRED);
        }
        BankAccountEntity a = new BankAccountEntity();
        a.setType(BankAccountType.CASH);
        a.setName(requireName(request.name()));
        a.setCurrency(requireCurrency(request.currency()));
        a.setOpeningBalance(request.openingBalance() == null ? BigDecimal.ZERO.setScale(2) : scale(request.openingBalance()));
        a.setArchived(Boolean.TRUE.equals(request.archived()));
        a.setIncludedInLedger(true);
        a.setHolderMatch(HolderMatch.UNKNOWN);
        boolean makeDefault = Boolean.TRUE.equals(request.defaultForCash());
        if (makeDefault && a.isArchived()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ACCOUNT_ARCHIVED);
        }
        if (makeDefault) {
            clearDefault(a.getCurrency(), null);
        }
        a.setDefaultForCash(makeDefault);
        BankAccountEntity saved = accountDao.save(a);
        audit.write(BankAccountEntity.class, saved.getId(), "CREATE", null, accountSnapshot(saved));
        return getAccount(saved.getId());
    }

    /**
     * Edits a cash account ({@code null} fields unchanged). Archiving clears {@code defaultForCash}; an
     * archived account cannot become the default; the currency changes only while there are no entries.
     */
    @Transactional
    public CashAccountDto updateAccount(Long id, CashAccountRequest request) {
        BankAccountEntity a = findCashAccount(id);
        if (request == null) {
            return getAccount(id);
        }
        Map<String, Object> before = accountSnapshot(a);
        if (request.name() != null) {
            a.setName(requireName(request.name()));
        }
        if (request.currency() != null) {
            String currency = requireCurrency(request.currency());
            if (!currency.equals(a.getCurrency())) {
                if (entryCount(a.getId()) > 0) {
                    throw new ForemenApiException(HttpStatus.CONFLICT, CURRENCY_LOCKED);
                }
                a.setDefaultForCash(false);
                a.setCurrency(currency);
            }
        }
        if (request.openingBalance() != null) {
            a.setOpeningBalance(scale(request.openingBalance()));
        }
        if (request.archived() != null) {
            a.setArchived(request.archived());
            if (a.isArchived()) {
                a.setDefaultForCash(false);
            }
        }
        if (request.defaultForCash() != null) {
            if (request.defaultForCash()) {
                if (a.isArchived()) {
                    throw new ForemenApiException(HttpStatus.CONFLICT, ACCOUNT_ARCHIVED);
                }
                if (!a.isDefaultForCash()) {
                    accountDao.save(a);
                    entityManager.flush();
                    clearDefault(a.getCurrency(), a.getId());
                }
                a.setDefaultForCash(true);
            } else {
                a.setDefaultForCash(false);
            }
        }
        BankAccountEntity saved = accountDao.save(a);
        entityManager.flush();
        audit.write(BankAccountEntity.class, saved.getId(), "UPDATE", before, accountSnapshot(saved));
        return getAccount(saved.getId());
    }

    /** Archives a cash account (it stops being the default). Idempotent. */
    @Transactional
    public CashAccountDto archiveAccount(Long id) {
        return updateAccount(id, new CashAccountRequest(null, null, null, null, Boolean.TRUE));
    }

    /**
     * The non-archived default cash account of {@code currency}; creates {@code "Cash {currency}"} flagged
     * default when none exists (R27.4).
     */
    @Transactional
    public BankAccountEntity defaultAccountOrCreate(String currency) {
        String cur = requireCurrency(currency);
        Optional<BankAccountEntity> existing = accountDao.findDefaultCashAccount(cur);
        if (existing.isPresent()) {
            return existing.get();
        }
        // An archived account may still carry the flag (written outside this service): clear it so the
        // partial UNIQUE (currency) WHERE default_for_cash index admits the new default.
        clearDefault(cur, null);
        BankAccountEntity a = new BankAccountEntity();
        a.setType(BankAccountType.CASH);
        a.setName("Cash " + cur);
        a.setCurrency(cur);
        a.setOpeningBalance(BigDecimal.ZERO.setScale(2));
        a.setDefaultForCash(true);
        a.setIncludedInLedger(true);
        a.setHolderMatch(HolderMatch.UNKNOWN);
        BankAccountEntity saved = accountDao.save(a);
        entityManager.flush();
        audit.write(BankAccountEntity.class, saved.getId(), "CREATE", null, accountSnapshot(saved));
        log.info("Created default cash account {} for currency {}", saved.getId(), cur);
        return saved;
    }

    // ============================================================================================
    // Manual entries (R27.3, R27.6)
    // ============================================================================================

    /**
     * Creates a {@code MANUAL} cash entry and, when {@code request.link()} is given, its link through
     * {@link BankLinkService#createLink} (effects as for bank lines, e.g. a cash {@code WORKER_PAYOUT} to a
     * {@code PLANNED} payout marks it paid with channel {@code CASH}). One transaction: a failing link
     * rolls the entry back.
     */
    @Transactional
    public CashEntryOutcome createManual(CashEntryRequest request) {
        if (request == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, AMOUNT_REQUIRED);
        }
        CashDirection direction = requireDirection(request.direction());
        BigDecimal magnitude = requirePositive(request.amount());
        LocalDate date = requireDate(request.date());
        BankAccountEntity account = request.accountId() == null
                ? accountDao.findDefaultCashAccount(DEFAULT_CURRENCY)
                        .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, ACCOUNT_REQUIRED))
                : findCashAccount(request.accountId());
        assertNotArchived(account);

        BankTransactionEntity txn = newCashLine(account, LedgerOrigin.MANUAL, direction.signed(magnitude), date,
                trim(request.counterpartyName()), trim(request.title()));
        txn = transactionDao.save(txn);
        entityManager.flush();
        audit.write(BankTransactionEntity.class, txn.getId(), "CREATE", null, entrySnapshot(txn));

        if (request.link() != null) {
            LinkOutcome out = linkService.createLink(txn.getId(), request.link(), false);
            return new CashEntryOutcome(txn.getId(), account.getId(), txn.getAmount(), out.linkStatus(), out.linkId());
        }
        return new CashEntryOutcome(txn.getId(), account.getId(), txn.getAmount(), txn.getLinkStatus(), null);
    }

    /**
     * Edits a {@code MANUAL} cash entry ({@code null} fields unchanged; {@code link} ignored). Rejected for
     * {@code SYSTEM} entries ({@link #ENTRY_SYSTEM_READ_ONLY}) and entries with a locked link
     * ({@link #ENTRY_NOT_EDITABLE}). With links, the direction cannot flip
     * ({@link BankLinkService#KIND_DIRECTION}) and the amount cannot drop below the linked sum
     * ({@link BankLinkService#OVER_ALLOCATED}).
     */
    @Transactional
    public CashEntryOutcome updateManual(Long transactionId, CashEntryRequest request) {
        BankTransactionEntity txn = findEditable(transactionId);
        if (request == null) {
            return outcome(txn);
        }
        Map<String, Object> before = entrySnapshot(txn);
        CashDirection direction = request.direction() == null ? CashDirection.of(txn.getAmount()) : request.direction();
        BigDecimal magnitude = request.amount() == null ? txn.getAmount().abs() : requirePositive(request.amount());
        BigDecimal signed = scale(direction.signed(magnitude));

        List<BankTransactionLinkEntity> links = linkDao.findByTransactionIdOrderByIdAsc(txn.getId());
        if (!links.isEmpty()) {
            if (CashDirection.of(txn.getAmount()) != direction) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.KIND_DIRECTION);
            }
            BigDecimal linked = links.stream().map(BankTransactionLinkEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (magnitude.compareTo(linked) < 0) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.OVER_ALLOCATED);
            }
        }
        if (request.accountId() != null && !request.accountId().equals(txn.getAccount().getId())) {
            BankAccountEntity target = findCashAccount(request.accountId());
            assertNotArchived(target);
            txn.setAccount(target);
            txn.setCurrency(target.getCurrency());
        }
        txn.setAmount(signed);
        if (request.date() != null) {
            txn.setOperationDate(request.date());
            txn.setBookingDate(request.date());
        }
        if (request.counterpartyName() != null) {
            txn.setCounterpartyName(trim(request.counterpartyName()));
        }
        if (request.title() != null) {
            txn.setTitle(trim(request.title()));
        }
        transactionDao.save(txn);
        entityManager.flush();
        audit.write(BankTransactionEntity.class, txn.getId(), "UPDATE", before, entrySnapshot(txn));
        LinkStatus status = linkService.refreshStatus(txn.getId());
        return new CashEntryOutcome(txn.getId(), txn.getAccount().getId(), txn.getAmount(), status, null);
    }


    /**
     * Deletes a {@code MANUAL} cash entry and its (unlocked) links; payouts keep their status (R16.8).
     *
     * @return {@code true} when a removed link pointed to a payout that stays {@code PAID}
     */
    @Transactional
    public boolean deleteManual(Long transactionId) {
        BankTransactionEntity txn = findEditable(transactionId);
        Map<String, Object> before = entrySnapshot(txn);
        boolean payoutStaysPaid = linkService.removeAllLinks(txn.getId()).payoutStaysPaid();
        transactionDao.delete(txn);
        entityManager.flush();
        audit.write(BankTransactionEntity.class, transactionId, "DELETE", before, null);
        return payoutStaysPaid;
    }

    // ============================================================================================
    // SYSTEM entries (R27.4, R28.1, Property 11)
    // ============================================================================================

    /**
     * A SYSTEM cash expense to record for a business source.
     *
     * @param source       payroll payout / procurement cash
     * @param sourceId     payout id / document id
     * @param accountId    the cash account, {@code null} → default of {@code currency} (auto-created)
     * @param currency     the currency used to resolve the default account
     * @param amount       positive magnitude (stored negative: cash out)
     * @param date         booking date
     * @param counterparty counterparty name, may be {@code null}
     * @param title        description, may be {@code null}
     * @param link         the link to create on the entry (amount {@code null} → line / target remainder)
     */
    public record SystemEntry(SystemSource source, Long sourceId, Long accountId, String currency,
                              BigDecimal amount, LocalDate date, String counterparty, String title,
                              LinkRequest link) {
    }

    /** The SYSTEM entry of a source, if any. */
    @Transactional(readOnly = true)
    public Optional<BankTransactionEntity> findSystemEntry(SystemSource source, Long sourceId) {
        return transactionDao.findBySystemSourceAndSystemSourceId(source, sourceId);
    }

    /**
     * Writes one SYSTEM expense entry + its link, in the caller's transaction. Refuses a second entry for
     * the same source with {@link #ALREADY_RECORDED} (the partial UNIQUE index is the race backstop).
     */
    @Transactional
    public CashEntryOutcome recordSystemEntry(SystemEntry entry) {
        if (entry.source() == null || entry.sourceId() == null) {
            throw new IllegalArgumentException("SYSTEM entry needs a source and a source id");
        }
        if (findSystemEntry(entry.source(), entry.sourceId()).isPresent()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ALREADY_RECORDED);
        }
        BigDecimal magnitude = requirePositive(entry.amount());
        LocalDate date = entry.date() == null ? LocalDate.now() : entry.date();
        BankAccountEntity account;
        if (entry.accountId() == null) {
            account = defaultAccountOrCreate(entry.currency() == null ? DEFAULT_CURRENCY : entry.currency());
        } else {
            account = findCashAccount(entry.accountId());
            assertNotArchived(account);
        }
        BankTransactionEntity txn = newCashLine(account, LedgerOrigin.SYSTEM, magnitude.negate(), date,
                trim(entry.counterparty()), trim(entry.title()));
        txn.setSystemSource(entry.source());
        txn.setSystemSourceId(entry.sourceId());
        txn = transactionDao.save(txn);
        entityManager.flush();
        audit.write(BankTransactionEntity.class, txn.getId(), "CREATE", null, entrySnapshot(txn));
        if (entry.link() == null) {
            return outcome(txn);
        }
        LinkOutcome out = linkService.createLink(txn.getId(), entry.link(), false);
        return new CashEntryOutcome(txn.getId(), account.getId(), txn.getAmount(), out.linkStatus(), out.linkId());
    }

    // ============================================================================================
    // Internals
    // ============================================================================================

    private BankTransactionEntity newCashLine(BankAccountEntity account, LedgerOrigin origin, BigDecimal signed,
                                              LocalDate date, String counterparty, String title) {
        BankTransactionEntity txn = new BankTransactionEntity();
        txn.setAccount(account);
        txn.setChannel(LedgerChannel.CASH);
        txn.setOrigin(origin);
        txn.setStatus(BankTxnStatus.POSTED);
        txn.setAmount(scale(signed));
        txn.setCurrency(account.getCurrency());
        txn.setOperationDate(date);
        txn.setBookingDate(date);
        txn.setCounterpartyName(truncate(counterparty));
        txn.setTitle(title);
        txn.setLinkStatus(LinkStatus.UNLINKED);
        txn.setCreatedByUser(BankLedgerAudit.currentUser());
        return txn;
    }

    /** A MANUAL cash line without locked links. */
    private BankTransactionEntity findEditable(Long transactionId) {
        BankTransactionEntity txn = transactionDao.findById(transactionId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (txn.getChannel() != LedgerChannel.CASH) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ENTRY_NOT_CASH);
        }
        if (txn.getOrigin() == LedgerOrigin.SYSTEM) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ENTRY_SYSTEM_READ_ONLY);
        }
        if (linkService.hasLockedLinks(txn.getId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ENTRY_NOT_EDITABLE);
        }
        return txn;
    }

    private BankAccountEntity findCashAccount(Long id) {
        BankAccountEntity a = accountDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (a.getType() != BankAccountType.CASH) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, NOT_CASH_ACCOUNT);
        }
        return a;
    }

    private void clearDefault(String currency, Long exceptId) {
        boolean changed = false;
        for (BankAccountEntity other : accountDao.findByTypeOrderByIdAsc(BankAccountType.CASH)) {
            if (other.isDefaultForCash() && currency.equals(other.getCurrency())
                    && (exceptId == null || !exceptId.equals(other.getId()))) {
                other.setDefaultForCash(false);
                accountDao.save(other);
                changed = true;
            }
        }
        if (changed) {
            entityManager.flush();
        }
    }

    private Map<Long, BigDecimal> entrySums(List<Long> accountIds) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (accountIds.isEmpty()) {
            return out;
        }
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT t.account.id, SUM(t.amount) FROM BankTransactionEntity t "
                                + "WHERE t.account.id IN :ids GROUP BY t.account.id", Object[].class)
                .setParameter("ids", accountIds)
                .getResultList();
        for (Object[] row : rows) {
            out.put((Long) row[0], nz((BigDecimal) row[1]));
        }
        return out;
    }

    private long entryCount(Long accountId) {
        return entityManager.createQuery(
                        "SELECT COUNT(t) FROM BankTransactionEntity t WHERE t.account.id = :id", Long.class)
                .setParameter("id", accountId)
                .getSingleResult();
    }

    private static CashAccountDto toDto(BankAccountEntity a, BigDecimal entrySum) {
        BigDecimal opening = nz(a.getOpeningBalance());
        return new CashAccountDto(a.getId(), a.getName(), a.getCurrency(), scale(opening),
                scale(opening.add(entrySum)), a.isDefaultForCash(), a.isArchived());
    }

    private static CashEntryOutcome outcome(BankTransactionEntity txn) {
        return new CashEntryOutcome(txn.getId(), txn.getAccount().getId(), txn.getAmount(), txn.getLinkStatus(), null);
    }

    private static void assertNotArchived(BankAccountEntity account) {
        if (account.isArchived()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ACCOUNT_ARCHIVED);
        }
    }

    private static CashDirection requireDirection(CashDirection direction) {
        if (direction == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DIRECTION_REQUIRED);
        }
        return direction;
    }

    private static BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, AMOUNT_REQUIRED);
        }
        BigDecimal scaled = scale(amount);
        if (scaled.signum() <= 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, AMOUNT_INVALID);
        }
        return scaled;
    }

    private static LocalDate requireDate(LocalDate date) {
        if (date == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DATE_REQUIRED);
        }
        return date;
    }

    private static String requireName(String name) {
        String n = trim(name);
        if (n == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, NAME_REQUIRED);
        }
        return truncate(n);
    }

    private static String requireCurrency(String currency) {
        String c = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(c).matches()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, CURRENCY_INVALID);
        }
        return c;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= NAME_MAX ? s : s.substring(0, NAME_MAX);
    }

    private static Map<String, Object> accountSnapshot(BankAccountEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("type", a.getType() == null ? null : a.getType().name());
        m.put("name", a.getName());
        m.put("currency", a.getCurrency());
        m.put("openingBalance", a.getOpeningBalance() == null ? null : a.getOpeningBalance().toPlainString());
        m.put("defaultForCash", a.isDefaultForCash());
        m.put("archived", a.isArchived());
        return m;
    }

    private static Map<String, Object> entrySnapshot(BankTransactionEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("accountId", t.getAccount() == null ? null : t.getAccount().getId());
        m.put("channel", t.getChannel() == null ? null : t.getChannel().name());
        m.put("origin", t.getOrigin() == null ? null : t.getOrigin().name());
        m.put("systemSource", t.getSystemSource() == null ? null : t.getSystemSource().name());
        m.put("systemSourceId", t.getSystemSourceId());
        m.put("amount", t.getAmount() == null ? null : t.getAmount().toPlainString());
        m.put("currency", t.getCurrency());
        m.put("bookingDate", t.getBookingDate() == null ? null : t.getBookingDate().toString());
        m.put("counterpartyName", t.getCounterpartyName());
        m.put("title", t.getTitle());
        return m;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
