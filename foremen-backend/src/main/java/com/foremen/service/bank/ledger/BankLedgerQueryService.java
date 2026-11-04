package com.foremen.service.bank.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankLinkedAmount;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.detect.DetectionCandidateProviders;
import com.foremen.service.bank.detect.MatchCandidate;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.RuleMatcher;
import com.foremen.service.bank.detect.TxnFacts;
import com.foremen.service.bank.ledger.LedgerDtos.CurrencyTotals;
import com.foremen.service.bank.ledger.LedgerDtos.Detection;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerDetail;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerPage;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerRow;
import com.foremen.service.bank.ledger.LedgerDtos.LedgerTotals;
import com.foremen.service.bank.ledger.LedgerDtos.LineCandidate;
import com.foremen.service.bank.ledger.LedgerDtos.LinkTarget;
import com.foremen.service.bank.ledger.LedgerDtos.LinkView;
import com.foremen.service.bank.ledger.LedgerDtos.PageInfo;
import com.foremen.service.bank.ledger.LedgerDtos.SuggestionView;
import com.foremen.service.bank.ledger.LedgerDtos.TargetType;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerDirection;
import com.foremen.service.bank.ledger.LedgerFilter.LedgerStatusFilter;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.DocumentPaymentStatusDeriver;
import com.foremen.service.bank.link.LinkEffects;
import com.foremen.service.payment.ScheduleStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * FOR-07-04 (Requirements 13, 17.2, 20.2–20.5, 21.6, 28.1; design §REST API) — the read side of the
 * ledger ({@code BANK_LEDGER} READ, checked at the controller):
 *
 * <ul>
 *   <li>{@link #list} — a filtered page of bank + cash lines ({@link LedgerSpecifications}) with the totals
 *       of the whole filter (per currency, R20.4);</li>
 *   <li>{@link #detail} — one line with links, open suggestions and (ADMIN only) the provider payload;</li>
 *   <li>{@link #linkTargets} — the target picker per link kind (R17.2);</li>
 *   <li>{@link #linkCandidates} — the reverse picker: lines that could pay a procurement document, ranked
 *       with the matcher's procurement scorer (R28.1 "Find bank transaction");</li>
 *   <li>{@link #idsForDetection} — the "re-run detection on a filtered selection" id resolution (R17.6).</li>
 * </ul>
 */
@Service
public class BankLedgerQueryService {

    /** 404 for an unknown line / document. */
    public static final String NOT_FOUND = "error.entity.not.found";

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;
    /** Max picker items per target type. */
    static final int TARGET_LIMIT = 50;
    /** Rows read from the DB before ranking a picker in memory. */
    static final int TARGET_SCAN = 300;
    /** Booking window around the document date for the reverse picker. */
    static final int CANDIDATE_WINDOW_DAYS = 90;

    static final Set<String> SORTABLE = Set.of("bookingDate", "operationDate", "amount", "id",
            "counterpartyName", "linkStatus", "channel");
    static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("bookingDate"), Sort.Order.desc("operationDate"),
            Sort.Order.desc("id"));

    static final Set<LinkKind> EXPENSE_KINDS = EnumSet.of(LinkKind.WORKER_PAYOUT, LinkKind.PROCUREMENT,
            LinkKind.OTHER_EXPENSE, LinkKind.INTERNAL_TRANSFER);
    static final Set<LinkKind> INCOME_KINDS = EnumSet.of(LinkKind.CLIENT_PAYMENT, LinkKind.OTHER_INCOME,
            LinkKind.INTERNAL_TRANSFER);
    static final Set<ProjectStatus> PICKER_PROJECT_STATUSES = EnumSet.of(ProjectStatus.APPROVED,
            ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD, ProjectStatus.COMPLETED);
    static final Set<DocumentPaymentStatus> DEFAULT_DOCUMENT_STATUSES = EnumSet.of(DocumentPaymentStatus.UNMATCHED,
            DocumentPaymentStatus.PARTIALLY_PAID);
    static final Set<LinkStatus> CANDIDATE_LINE_STATUSES = EnumSet.of(LinkStatus.UNLINKED, LinkStatus.SUGGESTED,
            LinkStatus.PARTIALLY_LINKED);
    /** Re-run detection on a selection applies to lines without links only (R17.6). */
    static final List<LedgerStatusFilter> DETECTABLE = List.of(LedgerStatusFilter.UNLINKED,
            LedgerStatusFilter.SUGGESTED);
    static final String WORKER_ROLE = "WORKER";

    private final BankTransactionDao transactionDao;
    private final BankTransactionLinkDao linkDao;
    private final BankTransactionSuggestionDao suggestionDao;
    private final BankAccountDao accountDao;
    private final WorkerPayoutDao payoutDao;
    private final ProcurementDocumentDao documentDao;
    private final PaymentScheduleDao scheduleDao;
    private final UnplannedExpenseCategoryDao categoryDao;
    private final ProjectDao projectDao;
    private final UserDao userDao;
    private final DetectionCandidateProviders providers;
    private final EntityManager entityManager;

    @SuppressWarnings("java:S107")
    public BankLedgerQueryService(BankTransactionDao transactionDao, BankTransactionLinkDao linkDao,
                                  BankTransactionSuggestionDao suggestionDao, BankAccountDao accountDao,
                                  WorkerPayoutDao payoutDao, ProcurementDocumentDao documentDao,
                                  PaymentScheduleDao scheduleDao, UnplannedExpenseCategoryDao categoryDao,
                                  ProjectDao projectDao, UserDao userDao, DetectionCandidateProviders providers,
                                  EntityManager entityManager) {
        this.transactionDao = transactionDao;
        this.linkDao = linkDao;
        this.suggestionDao = suggestionDao;
        this.accountDao = accountDao;
        this.payoutDao = payoutDao;
        this.documentDao = documentDao;
        this.scheduleDao = scheduleDao;
        this.categoryDao = categoryDao;
        this.projectDao = projectDao;
        this.userDao = userDao;
        this.providers = providers;
        this.entityManager = entityManager;
    }

    // ============================================================================================
    // List + totals (R20.2–R20.4)
    // ============================================================================================

    /** A filtered page of ledger lines plus the totals of the whole filter. */
    @Transactional(readOnly = true)
    public LedgerPage list(LedgerFilter filter, Pageable pageable) {
        LedgerFilter f = filter == null ? LedgerFilter.empty() : filter;
        Pageable p = sanitize(pageable);
        Page<BankTransactionEntity> page = transactionDao.findAll(LedgerSpecifications.of(f), p);
        List<LedgerRow> rows = toRows(page.getContent());
        return new LedgerPage(rows, new PageInfo(page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages()), totals(f));
    }

    /**
     * Totals of the filter: Σ income, Σ expense (positive), net per currency — {@code IGNORED} lines
     * excluded from the amounts (R17.5) — plus the count and the {@code To_Review} count.
     */
    @Transactional(readOnly = true)
    public LedgerTotals totals(LedgerFilter filter) {
        LedgerFilter f = filter == null ? LedgerFilter.empty() : filter;
        Specification<BankTransactionEntity> spec = LedgerSpecifications.of(f);
        long count = transactionDao.count(spec);
        long toReview = transactionDao.count(spec.and((root, q, cb) -> LedgerSpecifications.toReviewPredicate(root, cb)));
        Map<String, BigDecimal> income = sumByCurrency(f, true);
        Map<String, BigDecimal> expense = sumByCurrency(f, false);
        TreeMap<String, CurrencyTotals> byCurrency = new TreeMap<>();
        for (String cur : union(income.keySet(), expense.keySet())) {
            BigDecimal in = scale(income.getOrDefault(cur, BigDecimal.ZERO));
            BigDecimal out = scale(expense.getOrDefault(cur, BigDecimal.ZERO).abs());
            byCurrency.put(cur, new CurrencyTotals(cur, in, out, in.subtract(out)));
        }
        List<CurrencyTotals> list = List.copyOf(byCurrency.values());
        if (list.size() > 1) {
            return new LedgerTotals(null, null, null, count, toReview, list);
        }
        CurrencyTotals only = list.isEmpty() ? null : list.get(0);
        BigDecimal zero = scale(BigDecimal.ZERO);
        return new LedgerTotals(only == null ? zero : only.income(), only == null ? zero : only.expense(),
                only == null ? zero : only.net(), count, toReview, list);
    }

    /** Σ amount per currency of the non-ignored lines of the filter with the given sign. */
    private Map<String, BigDecimal> sumByCurrency(LedgerFilter f, boolean positive) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<BankTransactionEntity> root = q.from(BankTransactionEntity.class);
        Expression<BigDecimal> amount = root.get("amount");
        Predicate sign = positive ? cb.greaterThan(amount, BigDecimal.ZERO) : cb.lessThan(amount, BigDecimal.ZERO);
        Predicate where = cb.and(LedgerSpecifications.predicate(f, root, q, cb),
                cb.notEqual(root.get("linkStatus"), LinkStatus.IGNORED), sign);
        Expression<String> currency = root.get("currency");
        q.multiselect(currency, cb.sum(amount)).where(where).groupBy(currency);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Tuple t : entityManager.createQuery(q).getResultList()) {
            String cur = t.get(0, String.class);
            BigDecimal sum = t.get(1, BigDecimal.class);
            out.put(cur == null ? null : cur.strip(), sum == null ? BigDecimal.ZERO : sum);
        }
        return out;
    }

    /** Page size capped to {@link #MAX_PAGE_SIZE}; unknown sort properties dropped; default sort. */
    static Pageable sanitize(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, DEFAULT_SORT);
        }
        int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
        List<Sort.Order> orders = pageable.getSort().stream().filter(o -> SORTABLE.contains(o.getProperty())).toList();
        Sort sort = orders.isEmpty() ? DEFAULT_SORT : Sort.by(orders).and(Sort.by(Sort.Order.desc("id")));
        return PageRequest.of(Math.max(pageable.getPageNumber(), 0), size, sort);
    }

    // ============================================================================================
    // Detail (R20.5, R21.6)
    // ============================================================================================

    /**
     * One line: row fields, every line field, links, open suggestions. The provider payload and the raw
     * counterparty block are returned only when {@code includeProviderPayload} (ADMIN, R21.6).
     */
    @Transactional(readOnly = true)
    public LedgerDetail detail(Long transactionId, boolean includeProviderPayload) {
        BankTransactionEntity txn = findTxn(transactionId);
        List<BankTransactionLinkEntity> links = linkDao.findByTransactionIdOrderByIdAsc(txn.getId());
        List<BankTransactionSuggestionEntity> suggestions =
                suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(txn.getId(), SuggestionStatus.OPEN);
        Map<Long, BigDecimal> docSums = documentSums(links);
        LedgerRow row = row(txn, links, suggestions, docSums);
        BigDecimal linked = scale(links.stream().map(BankTransactionLinkEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal remaining = scale(txn.getAmount().abs().subtract(linked).max(BigDecimal.ZERO));
        return new LedgerDetail(row, txn.getExternalId(), txn.getSystemSource(), txn.getSystemSourceId(),
                txn.getCounterpartyKey(), txn.getProviderCategory(), txn.getIgnoredReason(), txn.getIgnoredBy(),
                txn.getIgnoredAt(), txn.getReviewedAt(), txn.getReviewedBy(), txn.getAiClassifiedAt(),
                txn.getIngestedAt(), txn.getCreatedByUser(), linked, remaining,
                suggestions.stream().map(this::suggestionView).toList(),
                includeProviderPayload ? txn.getCounterpartyRaw() : null,
                includeProviderPayload ? txn.getProviderPayload() : null);
    }

    // ============================================================================================
    // Link target pickers (R17.2)
    // ============================================================================================

    /**
     * Picker items for a link of {@code kind} on the line (R17.2):
     * <ul>
     *   <li>{@code WORKER_PAYOUT} — payouts ({@code PLANNED} first, then {@code PAID} not fully linked,
     *       nearest payout date first) + {@code WORKER} options for "create new payout";</li>
     *   <li>{@code PROCUREMENT} — documents in {@code paymentStatuses} (default UNMATCHED / PARTIALLY_PAID),
     *       optionally of one project, ranked by the matcher's procurement score;</li>
     *   <li>{@code CLIENT_PAYMENT} — published schedules ({@code FM-{id}} referenced project first);</li>
     *   <li>{@code OTHER_EXPENSE} — active categories + projects (optional project);</li>
     *   <li>{@code OTHER_INCOME} — projects;</li>
     *   <li>{@code INTERNAL_TRANSFER} — own bank and (non-archived) cash accounts except the line's.</li>
     * </ul>
     * A kind not allowed for the line direction → {@code 400 error.bank.link.kindDirection}.
     *
     * @param q free text over the item label (worker / supplier / invoice number / NIP / project / category /
     *          account name or IBAN), may be {@code null}
     */
    @Transactional(readOnly = true)
    public List<LinkTarget> linkTargets(Long transactionId, LinkKind kind, String q, Long projectId,
                                        Collection<DocumentPaymentStatus> paymentStatuses) {
        BankTransactionEntity txn = findTxn(transactionId);
        if (kind == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.TARGET_REQUIRED);
        }
        boolean income = txn.getAmount().signum() > 0;
        if (!(income ? INCOME_KINDS : EXPENSE_KINDS).contains(kind)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.KIND_DIRECTION);
        }
        String needle = needle(q);
        return switch (kind) {
            case WORKER_PAYOUT -> concat(payoutTargets(txn, needle), workerTargets(needle));
            case PROCUREMENT -> documentTargets(txn, needle, projectId, paymentStatuses);
            case CLIENT_PAYMENT -> scheduleTargets(txn, needle, projectId);
            case OTHER_EXPENSE -> concat(categoryTargets(needle), projectTargets(needle));
            case OTHER_INCOME -> projectTargets(needle);
            case INTERNAL_TRANSFER -> accountTargets(txn, needle);
        };
    }

    private List<LinkTarget> payoutTargets(BankTransactionEntity txn, String needle) {
        Specification<WorkerPayoutEntity> spec = (root, query, cb) -> {
            Predicate p = root.get("status").in(PayoutStatus.PLANNED, PayoutStatus.PAID);
            if (needle != null) {
                p = cb.and(p, cb.like(cb.lower(root.get("worker").get("name")),
                        "%" + LedgerSpecifications.escape(needle) + "%", '\\'));
            }
            return p;
        };
        List<WorkerPayoutEntity> rows = payoutDao.findAll(spec,
                PageRequest.of(0, TARGET_SCAN, Sort.by(Sort.Order.desc("payoutDate"), Sort.Order.desc("id"))))
                .getContent();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> linked = sums(linkDao.sumAmountByWorkerPayoutIds(
                rows.stream().map(WorkerPayoutEntity::getId).toList()));
        LocalDate anchor = effectiveDate(txn);
        record Item(WorkerPayoutEntity payout, BigDecimal remaining) {
        }
        List<Item> items = new ArrayList<>();
        for (WorkerPayoutEntity p : rows) {
            BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
            BigDecimal remaining = scale(amount.subtract(linked.getOrDefault(p.getId(), BigDecimal.ZERO)));
            if (p.getStatus() == PayoutStatus.PAID && remaining.signum() <= 0) {
                continue;
            }
            items.add(new Item(p, remaining.max(BigDecimal.ZERO)));
        }
        items.sort(Comparator.comparing((Item i) -> i.payout().getStatus() == PayoutStatus.PLANNED ? 0 : 1)
                .thenComparingLong(i -> distance(i.payout().getPayoutDate(), anchor))
                .thenComparing(i -> i.payout().getId()));
        return items.stream().limit(TARGET_LIMIT).map(i -> {
            WorkerPayoutEntity p = i.payout();
            UserEntity w = p.getWorker();
            String sub = p.getPeriodFrom() == null || p.getPeriodTo() == null ? null
                    : p.getPeriodFrom() + " – " + p.getPeriodTo();
            return new LinkTarget(TargetType.PAYOUT, p.getId(), w == null ? null : w.getName(), sub,
                    scaleOrNull(p.getAmount()), i.remaining(), null, p.getPayoutDate(), name(p.getStatus()), null,
                    null, null, w == null ? null : w.getId(), null);
        }).toList();
    }

    private List<LinkTarget> workerTargets(String needle) {
        return userDao.findByRoleCode(WORKER_ROLE).stream()
                .filter(UserEntity::isActive)
                .filter(u -> contains(needle, u.getName()))
                .sorted(Comparator.comparing((UserEntity u) -> u.getName() == null ? "" : u.getName(),
                        String.CASE_INSENSITIVE_ORDER).thenComparing(UserEntity::getId))
                .limit(TARGET_LIMIT)
                .map(u -> new LinkTarget(TargetType.WORKER, u.getId(), u.getName(), null, null, null, null, null,
                        null, null, null, null, u.getId(), null))
                .toList();
    }

    private List<LinkTarget> documentTargets(BankTransactionEntity txn, String needle, Long projectId,
                                             Collection<DocumentPaymentStatus> paymentStatuses) {
        Set<DocumentPaymentStatus> statuses = paymentStatuses == null || paymentStatuses.isEmpty()
                ? DEFAULT_DOCUMENT_STATUSES : EnumSet.copyOf(paymentStatuses);
        Specification<ProcurementDocumentEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (projectId != null) {
                ps.add(cb.equal(root.get("project").get("id"), projectId));
            }
            if (needle != null) {
                String pattern = "%" + LedgerSpecifications.escape(needle) + "%";
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("supplierName")), pattern, '\\'));
                any.add(cb.like(cb.lower(root.get("invoiceNumber")), pattern, '\\'));
                String digits = needle.replaceAll("\\D", "");
                if (digits.length() >= 3) {
                    any.add(cb.like(root.get("supplierNip"), "%" + digits + "%"));
                }
                ps.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        List<ProcurementDocumentEntity> rows = documentDao.findAll(spec,
                PageRequest.of(0, TARGET_SCAN, Sort.by(Sort.Order.desc("uploadedAt"), Sort.Order.desc("id"))))
                .getContent();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> linked = sums(linkDao.sumAmountByProcurementDocumentIds(
                rows.stream().map(ProcurementDocumentEntity::getId).toList()));
        TxnFacts facts = facts(txn, lineRemainderOrAbs(txn));
        List<CounterpartyRule> rules = rules(txn);
        record Item(ProcurementDocumentEntity doc, DocumentCandidate candidate, BigDecimal score) {
        }
        List<Item> items = new ArrayList<>();
        for (ProcurementDocumentEntity d : rows) {
            DocumentCandidate c = DetectionCandidateProviders.toDocumentCandidate(d, linked.get(d.getId()));
            if (!statuses.contains(c.paymentStatus())) {
                continue;
            }
            BigDecimal score = facts == null ? BigDecimal.ZERO
                    : RuleMatcher.scoreProcurement(facts, c, rules).map(MatchCandidate::score).orElse(BigDecimal.ZERO);
            items.add(new Item(d, c, score));
        }
        items.sort(Comparator.comparing(Item::score, Comparator.reverseOrder())
                .thenComparing(i -> i.doc().getUploadedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(i -> i.doc().getId(), Comparator.reverseOrder()));
        return items.stream().limit(TARGET_LIMIT).map(i -> {
            ProcurementDocumentEntity d = i.doc();
            BigDecimal total = d.getDocumentTotal();
            BigDecimal remaining = total == null ? null
                    : scale(total.subtract(linked.getOrDefault(d.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO));
            ProjectEntity project = d.getProject();
            return new LinkTarget(TargetType.DOCUMENT, d.getId(), documentLabel(d), d.getInvoiceNumber(),
                    scaleOrNull(total), remaining, null,
                    d.getInvoiceDate() != null ? d.getInvoiceDate() : toDate(d.getUploadedAt()),
                    name(d.getStatus()), i.candidate().paymentStatus(),
                    project == null ? null : project.getId(), project == null ? null : project.getName(), null,
                    i.score());
        }).toList();
    }

    private List<LinkTarget> scheduleTargets(BankTransactionEntity txn, String needle, Long projectId) {
        Set<Long> referenced = RuleMatcher.projectReferences(txn.getTitle());
        record Item(PaymentScheduleEntity schedule, BigDecimal open) {
        }
        List<Item> items = new ArrayList<>();
        for (PaymentScheduleEntity s : scheduleDao.findByStatus(ScheduleStatus.PUBLISHED)) {
            ProjectEntity p = s.getProject();
            if (p == null || (projectId != null && !projectId.equals(p.getId()))
                    || !(contains(needle, p.getName()) || needle != null && needle.equals("fm-" + p.getId()))) {
                continue;
            }
            items.add(new Item(s, openRemainder(s)));
        }
        items.sort(Comparator.comparing((Item i) -> referenced.contains(i.schedule().getProject().getId()) ? 0 : 1)
                .thenComparing(i -> nz(i.schedule().getProject().getName()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(i -> i.schedule().getId()));
        return items.stream().limit(TARGET_LIMIT).map(i -> {
            PaymentScheduleEntity s = i.schedule();
            ProjectEntity p = s.getProject();
            return new LinkTarget(TargetType.SCHEDULE, s.getId(), p.getName(), "FM-" + p.getId(), i.open(), null,
                    s.getCurrency() == null ? null : s.getCurrency().strip(), null, name(s.getStatus()), null,
                    p.getId(), p.getName(), null, null);
        }).toList();
    }

    private List<LinkTarget> categoryTargets(String needle) {
        boolean ru = isRussian();
        return StreamSupport.stream(categoryDao.findAll().spliterator(), false)
                .filter(UnplannedExpenseCategoryEntity::isActive)
                .filter(c -> contains(needle, c.getCode()) || contains(needle, c.getNamePL())
                        || contains(needle, c.getNameRU()))
                .sorted(Comparator.comparing(UnplannedExpenseCategoryEntity::getId))
                .limit(TARGET_LIMIT)
                .map(c -> new LinkTarget(TargetType.CATEGORY, c.getId(), ru ? c.getNameRU() : c.getNamePL(),
                        c.getCode(), null, null, null, null, null, null, null, null, null, null))
                .toList();
    }

    private List<LinkTarget> projectTargets(String needle) {
        return projectDao.findByStatusInOrderByIdAsc(PICKER_PROJECT_STATUSES).stream()
                .filter(p -> contains(needle, p.getName()) || contains(needle, p.getFormattedAddress())
                        || contains(needle, p.getAddress()))
                .sorted(Comparator.comparing(ProjectEntity::getId, Comparator.reverseOrder()))
                .limit(TARGET_LIMIT)
                .map(p -> new LinkTarget(TargetType.PROJECT, p.getId(), p.getName(),
                        p.getFormattedAddress() != null ? p.getFormattedAddress() : p.getAddress(), null, null, null,
                        null, name(p.getStatus()), null, p.getId(), p.getName(), null, null))
                .toList();
    }

    private List<LinkTarget> accountTargets(BankTransactionEntity txn, String needle) {
        Long own = txn.getAccount() == null ? null : txn.getAccount().getId();
        String cpIban = txn.getCounterpartyIban();
        String ibanNeedle = needle == null ? null : LedgerSpecifications.ibanFragment(needle);
        return StreamSupport.stream(accountDao.findAll().spliterator(), false)
                .filter(a -> !Objects.equals(a.getId(), own))
                .filter(a -> !(a.getType() == BankAccountType.CASH && a.isArchived()))
                .filter(a -> contains(needle, a.getName()) || contains(needle, bankName(a.getConnection()))
                        || (ibanNeedle != null && a.getIban() != null && a.getIban().contains(ibanNeedle)))
                .sorted(Comparator.comparing((BankAccountEntity a) -> cpIban != null && cpIban.equals(a.getIban()) ? 0 : 1)
                        .thenComparing(BankAccountEntity::getType)
                        .thenComparing(BankAccountEntity::getId))
                .limit(TARGET_LIMIT)
                .map(a -> new LinkTarget(TargetType.ACCOUNT, a.getId(), accountLabel(a),
                        a.getType() == BankAccountType.CASH ? null : bankName(a.getConnection()),
                        a.getType() == BankAccountType.BANK ? scaleOrNull(a.getBalance()) : null, null,
                        a.getCurrency() == null ? null : a.getCurrency().strip(), null, a.getType().name(), null,
                        null, null, null, null))
                .toList();
    }

    // ============================================================================================
    // Reverse picker: lines for a document (R28.1)
    // ============================================================================================

    /**
     * Unlinked / suggested / partially linked {@code POSTED} expense lines (bank and cash, not ignored)
     * booked within ±{@value #CANDIDATE_WINDOW_DAYS} days of the document's invoice (else upload) date,
     * ranked by {@link RuleMatcher#scoreProcurement} on the line remainder, then by date distance.
     */
    @Transactional(readOnly = true)
    public List<LineCandidate> linkCandidates(Long procurementDocumentId) {
        if (procurementDocumentId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.TARGET_REQUIRED);
        }
        ProcurementDocumentEntity doc = documentDao.findById(procurementDocumentId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        DocumentCandidate candidate = DetectionCandidateProviders.toDocumentCandidate(doc,
                linkDao.sumAmountByProcurementDocumentId(doc.getId()));
        LocalDate anchor = doc.getInvoiceDate() != null ? doc.getInvoiceDate()
                : doc.getUploadedAt() != null ? toDate(doc.getUploadedAt()) : LocalDate.now(ZoneOffset.UTC);
        List<BankTransactionEntity> lines = transactionDao.findExpenseLinesForDocumentMatching(CANDIDATE_LINE_STATUSES,
                anchor.minusDays(CANDIDATE_WINDOW_DAYS), anchor.plusDays(CANDIDATE_WINDOW_DAYS));
        if (lines.isEmpty()) {
            return List.of();
        }
        Map<Long, List<BankTransactionLinkEntity>> linksByLine = linksByLine(lines);
        Map<String, List<CounterpartyRule>> ruleCache = new HashMap<>();
        record Scored(BankTransactionEntity line, BigDecimal remaining, BigDecimal score, List<String> reasons) {
        }
        List<Scored> scored = new ArrayList<>();
        for (BankTransactionEntity line : lines) {
            if (line.getIgnoredReason() != null || line.getBookingDate() == null) {
                continue;
            }
            BigDecimal linked = linksByLine.getOrDefault(line.getId(), List.of()).stream()
                    .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remaining = scale(line.getAmount().abs().subtract(linked));
            if (remaining.signum() <= 0) {
                continue;
            }
            TxnFacts facts = new TxnFacts(TxnFacts.Direction.EXPENSE, remaining, line.getBookingDate(),
                    line.getTitle(), line.getCounterpartyName(), line.getCounterpartyIban(), line.getCounterpartyKey());
            List<CounterpartyRule> rules = line.getCounterpartyKey() == null ? List.of()
                    : ruleCache.computeIfAbsent(line.getCounterpartyKey(), providers::counterpartyRules);
            Optional<MatchCandidate> match = RuleMatcher.scoreProcurement(facts, candidate, rules);
            scored.add(new Scored(line, remaining, match.map(MatchCandidate::score).orElse(BigDecimal.ZERO),
                    match.map(MatchCandidate::reasons).orElse(List.of())));
        }
        scored.sort(Comparator.comparing(Scored::score, Comparator.reverseOrder())
                .thenComparingLong(s -> distance(s.line().getBookingDate(), anchor))
                .thenComparing(s -> s.line().getId(), Comparator.reverseOrder()));
        List<Scored> top = scored.stream().limit(TARGET_LIMIT).toList();
        Map<Long, LedgerRow> rows = new HashMap<>();
        for (LedgerRow r : toRows(top.stream().map(Scored::line).toList())) {
            rows.put(r.id(), r);
        }
        return top.stream()
                .map(s -> new LineCandidate(rows.get(s.line().getId()), s.remaining(), s.score(), s.reasons()))
                .toList();
    }

    // ============================================================================================
    // Selection helpers (R17.6, R20.7)
    // ============================================================================================

    /**
     * Ids of the filter's lines eligible for "re-run detection" ({@code UNLINKED} / {@code SUGGESTED},
     * R17.6), oldest first, at most {@code cap}. A filter that already restricts the link status is
     * intersected with that set.
     */
    @Transactional(readOnly = true)
    public List<Long> idsForDetection(LedgerFilter filter, int cap) {
        LedgerFilter f = filter == null ? LedgerFilter.empty() : filter;
        Specification<BankTransactionEntity> spec = LedgerSpecifications.of(f)
                .and(LedgerSpecifications.of(LedgerFilter.empty().withLinkStatuses(DETECTABLE)));
        return transactionDao.findAll(spec, PageRequest.of(0, Math.max(cap, 1), Sort.by(Sort.Order.asc("id"))))
                .stream().map(BankTransactionEntity::getId).toList();
    }

    /** The current link status of a line (404 when unknown). */
    @Transactional(readOnly = true)
    public LinkStatus linkStatus(Long transactionId) {
        return findTxn(transactionId).getLinkStatus();
    }

    /** The best-ranked {@code OPEN} suggestion of a line, if any. */
    @Transactional(readOnly = true)
    public Optional<Long> topOpenSuggestionId(Long transactionId) {
        return suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(transactionId, SuggestionStatus.OPEN)
                .stream().findFirst().map(BankTransactionSuggestionEntity::getId);
    }

    // ============================================================================================
    // Row mapping
    // ============================================================================================

    /** Rows of {@code lines} (order kept) with their links and open suggestions loaded in two batches. */
    List<LedgerRow> toRows(List<BankTransactionEntity> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Long> ids = lines.stream().map(BankTransactionEntity::getId).toList();
        Map<Long, List<BankTransactionLinkEntity>> links = linksByLine(lines);
        Map<Long, List<BankTransactionSuggestionEntity>> suggestions = new HashMap<>();
        for (BankTransactionSuggestionEntity s : suggestionDao.findByTransactionIdInAndStatus(ids, SuggestionStatus.OPEN)) {
            suggestions.computeIfAbsent(s.getTransaction().getId(), k -> new ArrayList<>()).add(s);
        }
        suggestions.values().forEach(l -> l.sort(Comparator.comparingInt(BankTransactionSuggestionEntity::getRank)
                .thenComparing(BankTransactionSuggestionEntity::getId)));
        Map<Long, BigDecimal> docSums = documentSums(links.values().stream().flatMap(List::stream).toList());
        List<LedgerRow> out = new ArrayList<>(lines.size());
        for (BankTransactionEntity t : lines) {
            out.add(row(t, links.getOrDefault(t.getId(), List.of()), suggestions.getOrDefault(t.getId(), List.of()),
                    docSums));
        }
        return out;
    }

    private Map<Long, List<BankTransactionLinkEntity>> linksByLine(List<BankTransactionEntity> lines) {
        Map<Long, List<BankTransactionLinkEntity>> out = new HashMap<>();
        for (BankTransactionLinkEntity l : linkDao.findByTransactionIdIn(
                lines.stream().map(BankTransactionEntity::getId).toList())) {
            out.computeIfAbsent(l.getTransaction().getId(), k -> new ArrayList<>()).add(l);
        }
        out.values().forEach(l -> l.sort(Comparator.comparing(BankTransactionLinkEntity::getId)));
        return out;
    }

    private Map<Long, BigDecimal> documentSums(List<BankTransactionLinkEntity> links) {
        List<Long> docIds = links.stream()
                .filter(l -> l.getProcurementDocument() != null)
                .map(l -> l.getProcurementDocument().getId())
                .distinct()
                .toList();
        return docIds.isEmpty() ? Map.of() : sums(linkDao.sumAmountByProcurementDocumentIds(docIds));
    }

    private LedgerRow row(BankTransactionEntity t, List<BankTransactionLinkEntity> links,
                          List<BankTransactionSuggestionEntity> suggestions, Map<Long, BigDecimal> docSums) {
        BankAccountEntity account = t.getAccount();
        BankConnectionEntity connection = account == null ? null : account.getConnection();
        boolean locked = links.stream().anyMatch(LinkEffects::isLocked);
        return new LedgerRow(t.getId(), t.getChannel(), t.getOrigin(), t.getStatus(), t.getBookingDate(),
                t.getOperationDate(), t.getAmount().signum() < 0 ? LedgerDirection.EXPENSE : LedgerDirection.INCOME,
                scale(t.getAmount()), t.getCurrency() == null ? null : t.getCurrency().strip(),
                bankName(connection), connection == null ? null : connection.getBankLogoUrl(),
                connection == null ? null : connection.getId(), account == null ? null : account.getId(),
                account == null ? null : account.getName(),
                account == null ? null : last4(account.getIban() != null ? account.getIban() : account.getAccountNumber()),
                t.getCounterpartyName(), t.getCounterpartyIban(), t.getTitle(), t.getLinkStatus(), locked,
                t.getOrigin() == LedgerOrigin.MANUAL && !locked, detection(links, suggestions),
                links.stream().map(l -> linkView(l, docSums)).toList(), suggestions.size());
    }

    /** The RULE / AI source of the first such link, else of the top open suggestion; none otherwise. */
    static Detection detection(List<BankTransactionLinkEntity> links, List<BankTransactionSuggestionEntity> suggestions) {
        for (BankTransactionLinkEntity l : links) {
            if (l.getSource() == DetectionSource.RULE || l.getSource() == DetectionSource.AI) {
                return new Detection(l.getSource(), l.getConfidence(), null);
            }
        }
        if (!suggestions.isEmpty()) {
            BankTransactionSuggestionEntity s = suggestions.get(0);
            return new Detection(s.getSource(), s.getConfidence(), s.getRationale());
        }
        return null;
    }

    private LinkView linkView(BankTransactionLinkEntity l, Map<Long, BigDecimal> docSums) {
        Target t = target(l.getKind(), l.getWorkerPayout(), l.getProcurementDocument(), l.getPaymentSchedule(),
                l.getProject(), l.getUnplannedCategory(), l.getCounterAccount(), l.getNote());
        ProcurementDocumentEntity doc = l.getProcurementDocument();
        DocumentPaymentStatus docStatus = null;
        if (doc != null) {
            BigDecimal sum = docSums.getOrDefault(doc.getId(), BigDecimal.ZERO);
            docStatus = DocumentPaymentStatusDeriver.derive(doc.getDocumentTotal(),
                    sum.signum() > 0 ? List.of(sum) : List.of());
        }
        return new LinkView(l.getId(), l.getKind(), scale(l.getAmount()), t.label(), t.projectId(), t.projectName(),
                t.url(), l.getSource(), l.getConfidence(), l.getConfirmedAt() != null || l.getSource() == DetectionSource.MANUAL,
                l.getConfirmedAt(), l.getConfirmedBy(), LinkEffects.isLocked(l), l.getNote(),
                id(l.getWorkerPayout()), doc == null ? null : doc.getId(), docStatus,
                l.getPaymentSchedule() == null ? null : l.getPaymentSchedule().getId(),
                l.getPaymentTransaction() == null ? null : l.getPaymentTransaction().getId(),
                l.getUnplannedCategory() == null ? null : l.getUnplannedCategory().getId(),
                l.getCounterAccount() == null ? null : l.getCounterAccount().getId());
    }

    private SuggestionView suggestionView(BankTransactionSuggestionEntity s) {
        Target t = target(s.getKind(), s.getWorkerPayout(), s.getProcurementDocument(), s.getPaymentSchedule(),
                s.getProject(), s.getUnplannedCategory(), s.getCounterAccount(), s.getNote());
        return new SuggestionView(s.getId(), s.getKind(), scale(s.getAmount()), s.getRank(), s.getRationale(),
                s.getSource(), s.getConfidence(), t.label(), t.projectId(), t.projectName(), t.url(),
                id(s.getWorkerPayout()),
                s.getProcurementDocument() == null ? null : s.getProcurementDocument().getId(),
                s.getPaymentSchedule() == null ? null : s.getPaymentSchedule().getId(),
                s.getUnplannedCategory() == null ? null : s.getUnplannedCategory().getId(),
                s.getCounterAccount() == null ? null : s.getCounterAccount().getId());
    }

    /** Label / project / UI URL of a link or suggestion target (design §Frontend "Linked-entity URLs"). */
    record Target(String label, Long projectId, String projectName, String url) {
    }

    @SuppressWarnings("java:S107")
    private Target target(LinkKind kind, WorkerPayoutEntity payout, ProcurementDocumentEntity doc,
                          PaymentScheduleEntity schedule, ProjectEntity linkProject,
                          UnplannedExpenseCategoryEntity category, BankAccountEntity counterAccount, String note) {
        ProjectEntity project = linkProject;
        if (project == null && doc != null) {
            project = doc.getProject();
        }
        if (project == null && schedule != null) {
            project = schedule.getProject();
        }
        Long projectId = project == null ? null : project.getId();
        String projectName = project == null ? null : project.getName();
        String projectUrl = projectId == null ? null : "/projects/" + projectId;
        return switch (kind) {
            case WORKER_PAYOUT -> {
                UserEntity w = payout == null ? null : payout.getWorker();
                yield new Target(w == null ? null : w.getName(), projectId, projectName,
                        w == null ? null : "/company/payroll/" + w.getId());
            }
            case PROCUREMENT -> new Target(doc == null ? null : documentLabel(doc), projectId, projectName,
                    doc == null || projectId == null ? null
                            : "/projects/" + projectId + "/purchases?documentId=" + doc.getId());
            case CLIENT_PAYMENT -> new Target(projectName, projectId, projectName,
                    projectId == null ? null : "/projects/" + projectId + "/payments");
            case OTHER_EXPENSE -> new Target(category == null ? null
                    : (isRussian() ? category.getNameRU() : category.getNamePL()), projectId, projectName, projectUrl);
            case OTHER_INCOME -> new Target(projectName != null ? projectName : note, projectId, projectName, projectUrl);
            case INTERNAL_TRANSFER -> new Target(counterAccount == null ? null : accountLabel(counterAccount),
                    projectId, projectName, null);
        };
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private BankTransactionEntity findTxn(Long id) {
        if (id == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        return transactionDao.findById(id).orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private List<CounterpartyRule> rules(BankTransactionEntity txn) {
        return txn.getCounterpartyKey() == null ? List.of() : providers.counterpartyRules(txn.getCounterpartyKey());
    }

    /** Facts of a line for the procurement scorer (EXPENSE only), with {@code amount} as the magnitude. */
    private static TxnFacts facts(BankTransactionEntity txn, BigDecimal amount) {
        if (txn.getAmount().signum() >= 0) {
            return null;
        }
        return new TxnFacts(TxnFacts.Direction.EXPENSE, amount, effectiveDate(txn), txn.getTitle(),
                txn.getCounterpartyName(), txn.getCounterpartyIban(), txn.getCounterpartyKey());
    }

    /** The line's unallocated remainder, or its full |amount| when nothing is left (still a useful hint). */
    private BigDecimal lineRemainderOrAbs(BankTransactionEntity txn) {
        BigDecimal linked = linkDao.findByTransactionIdOrderByIdAsc(txn.getId()).stream()
                .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainder = txn.getAmount().abs().subtract(linked);
        return remainder.signum() > 0 ? remainder : txn.getAmount().abs();
    }

    private static BigDecimal openRemainder(PaymentScheduleEntity s) {
        BigDecimal open = BigDecimal.ZERO;
        for (PaymentTrancheEntity t : s.getTranches()) {
            BigDecimal r = nzAmount(t.getAmountGross()).subtract(nzAmount(t.getPaidGross()));
            if (r.signum() > 0) {
                open = open.add(r);
            }
        }
        return scale(open);
    }

    static LocalDate effectiveDate(BankTransactionEntity txn) {
        return txn.getBookingDate() != null ? txn.getBookingDate() : txn.getOperationDate();
    }

    private static long distance(LocalDate a, LocalDate b) {
        if (a == null || b == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    static String documentLabel(ProcurementDocumentEntity d) {
        String supplier = d.getSupplierName() == null || d.getSupplierName().isBlank() ? null : d.getSupplierName();
        String number = d.getInvoiceNumber() == null || d.getInvoiceNumber().isBlank() ? null : d.getInvoiceNumber();
        if (supplier == null && number == null) {
            return "#" + d.getId();
        }
        if (supplier == null) {
            return number;
        }
        return number == null ? supplier : supplier + " " + number;
    }

    static String accountLabel(BankAccountEntity a) {
        String name = a.getName() == null || a.getName().isBlank() ? null : a.getName();
        String last4 = last4(a.getIban() != null ? a.getIban() : a.getAccountNumber());
        if (name == null) {
            return last4 == null ? "#" + a.getId() : "…" + last4;
        }
        return last4 == null ? name : name + " …" + last4;
    }

    static String bankName(BankConnectionEntity c) {
        if (c == null) {
            return null;
        }
        return c.getBankName() != null && !c.getBankName().isBlank() ? c.getBankName() : c.getDisplayName();
    }

    static String last4(String value) {
        if (value == null) {
            return null;
        }
        String compact = value.replaceAll("\\s+", "");
        return compact.length() <= 4 ? compact : compact.substring(compact.length() - 4);
    }

    private static boolean isRussian() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /** Lower-cased trimmed query, {@code null} when blank. */
    static String needle(String q) {
        return q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String needle, String value) {
        return needle == null || (value != null && value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.TreeSet<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static Map<Long, BigDecimal> sums(List<BankLinkedAmount> rows) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (BankLinkedAmount r : rows) {
            out.put(r.targetId(), r.total() == null ? BigDecimal.ZERO : r.total());
        }
        return out;
    }

    private static Long id(WorkerPayoutEntity p) {
        return p == null ? null : p.getId();
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal nzAmount(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static LocalDate toDate(OffsetDateTime ts) {
        return ts == null ? null : ts.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleOrNull(BigDecimal v) {
        return v == null ? null : scale(v);
    }
}
