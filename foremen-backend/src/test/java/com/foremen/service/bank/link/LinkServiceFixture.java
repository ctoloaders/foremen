package com.foremen.service.bank.link;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankCounterpartyRuleDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.PaymentTransactionDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankCounterpartyRuleEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.LedgerChannel;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTransactionEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.payment.PaymentTransactionService;
import com.foremen.service.payment.dto.BankTransferInput;
import com.foremen.service.payroll.WorkerPayoutService;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

import jakarta.persistence.EntityManager;

/**
 * Test fixture for {@link BankLinkService}: the DAOs are Mockito mocks backed by in-memory stores (so the
 * Σ-link queries see the service's own writes), the payroll / payment seams are mocks that record their
 * calls and mimic the state change (a payout marked paid becomes {@code PAID}; a recorded bank transfer
 * returns a fresh payment transaction id). Usable from JUnit and jqwik (no extension needed).
 */
final class LinkServiceFixture {

    static final LocalDate BOOKING = LocalDate.of(2026, 3, 14);

    final AtomicLong ids = new AtomicLong(1000);

    final Map<Long, BankTransactionEntity> txns = new HashMap<>();
    final List<BankTransactionLinkEntity> links = new ArrayList<>();
    final List<BankTransactionSuggestionEntity> suggestions = new ArrayList<>();
    final Map<Long, WorkerPayoutEntity> payouts = new HashMap<>();
    final Map<Long, ProcurementDocumentEntity> documents = new HashMap<>();
    final Map<Long, PaymentScheduleEntity> schedules = new HashMap<>();
    final Map<Long, ProjectEntity> projects = new HashMap<>();
    final Map<Long, UnplannedExpenseCategoryEntity> categories = new HashMap<>();
    final Map<Long, BankAccountEntity> accounts = new HashMap<>();
    final Map<Long, UserEntity> users = new HashMap<>();
    final Map<String, BankCounterpartyRuleEntity> rules = new HashMap<>();
    final List<BankTransferInput> transfers = new ArrayList<>();
    final List<Long> transferSchedules = new ArrayList<>();

    final BankTransactionDao transactionDao = mock(BankTransactionDao.class);
    final BankTransactionLinkDao linkDao = mock(BankTransactionLinkDao.class);
    final BankTransactionSuggestionDao suggestionDao = mock(BankTransactionSuggestionDao.class);
    final WorkerPayoutDao workerPayoutDao = mock(WorkerPayoutDao.class);
    final ProcurementDocumentDao documentDao = mock(ProcurementDocumentDao.class);
    final PaymentScheduleDao scheduleDao = mock(PaymentScheduleDao.class);
    final ProjectDao projectDao = mock(ProjectDao.class);
    final UnplannedExpenseCategoryDao categoryDao = mock(UnplannedExpenseCategoryDao.class);
    final BankAccountDao accountDao = mock(BankAccountDao.class);
    final UserDao userDao = mock(UserDao.class);
    final BankCounterpartyRuleDao ruleDao = mock(BankCounterpartyRuleDao.class);
    final PaymentTransactionDao paymentTransactionDao = mock(PaymentTransactionDao.class);
    final AuditLogDao auditLogDao = mock(AuditLogDao.class);
    final WorkerPayoutService workerPayoutService = mock(WorkerPayoutService.class);
    final PaymentTransactionService paymentTransactionService = mock(PaymentTransactionService.class);
    final EntityManager entityManager = mock(EntityManager.class);

    final BankLedgerAudit audit = new BankLedgerAudit(auditLogDao);
    final CounterpartyRuleService ruleService = new CounterpartyRuleService(ruleDao, userDao, projectDao,
            scheduleDao, categoryDao, accountDao, audit);
    final LinkEffects effects = new LinkEffects(workerPayoutService, paymentTransactionService,
            paymentTransactionDao);
    final BankLinkService service = new BankLinkService(transactionDao, linkDao, suggestionDao, workerPayoutDao,
            documentDao, scheduleDao, projectDao, categoryDao, accountDao, workerPayoutService, effects,
            ruleService, audit, entityManager);

    LinkServiceFixture() {
        byId(transactionDao, txns);
        byId(workerPayoutDao, payouts);
        byId(documentDao, documents);
        byId(scheduleDao, schedules);
        byId(projectDao, projects);
        byId(categoryDao, categories);
        byId(accountDao, accounts);
        byId(userDao, users);
        when(transactionDao.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Links
        when(linkDao.save(any())).thenAnswer(inv -> {
            BankTransactionLinkEntity l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId(ids.incrementAndGet());
            }
            if (links.stream().noneMatch(x -> x == l)) {
                links.add(l);
            }
            return l;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            BankTransactionLinkEntity l = inv.getArgument(0);
            links.removeIf(x -> Objects.equals(x.getId(), l.getId()));
            return null;
        }).when(linkDao).delete(any(BankTransactionLinkEntity.class));
        when(linkDao.findById(anyLong())).thenAnswer(inv -> links.stream()
                .filter(l -> l.getId().equals(inv.getArgument(0))).findFirst());
        when(linkDao.findByTransactionIdOrderByIdAsc(anyLong())).thenAnswer(inv -> links.stream()
                .filter(l -> l.getTransaction().getId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(BankTransactionLinkEntity::getId)).toList());
        when(linkDao.sumAmountByWorkerPayoutId(anyLong())).thenAnswer(inv -> links.stream()
                .filter(l -> l.getWorkerPayout() != null && l.getWorkerPayout().getId().equals(inv.getArgument(0)))
                .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        when(linkDao.sumAmountByProcurementDocumentId(anyLong())).thenAnswer(inv -> links.stream()
                .filter(l -> l.getProcurementDocument() != null
                        && l.getProcurementDocument().getId().equals(inv.getArgument(0)))
                .map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        // Suggestions
        when(suggestionDao.save(any())).thenAnswer(inv -> {
            BankTransactionSuggestionEntity s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(ids.incrementAndGet());
            }
            if (suggestions.stream().noneMatch(x -> x == s)) {
                suggestions.add(s);
            }
            return s;
        });
        when(suggestionDao.findById(anyLong())).thenAnswer(inv -> suggestions.stream()
                .filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        when(suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(anyLong(), any())).thenAnswer(inv ->
                suggestions.stream()
                        .filter(s -> s.getTransaction().getId().equals(inv.getArgument(0))
                                && s.getStatus() == inv.getArgument(1))
                        .sorted(Comparator.comparingInt(BankTransactionSuggestionEntity::getRank)).toList());
        when(suggestionDao.countByTransactionIdAndStatus(anyLong(), any())).thenAnswer(inv -> suggestions.stream()
                .filter(s -> s.getTransaction().getId().equals(inv.getArgument(0))
                        && s.getStatus() == inv.getArgument(1)).count());

        // Rules
        when(ruleDao.findByCounterpartyKey(any())).thenAnswer(inv -> Optional.ofNullable(rules.get(inv.getArgument(0))));
        when(ruleDao.save(any())).thenAnswer(inv -> {
            BankCounterpartyRuleEntity r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(ids.incrementAndGet());
            }
            rules.put(r.getCounterpartyKey(), r);
            return r;
        });

        // Payroll seam: PLANNED → PAID with the given channel / date.
        when(workerPayoutService.markPaidFromLedger(anyLong(), any(), any())).thenAnswer(inv -> {
            WorkerPayoutEntity p = payouts.get(inv.<Long>getArgument(0));
            if (p.getStatus() != PayoutStatus.PLANNED) {
                throw new IllegalStateException("payout not PLANNED");
            }
            p.setStatus(PayoutStatus.PAID);
            p.setPaymentChannel(inv.getArgument(2));
            return null;
        });
        when(workerPayoutService.create(any(CreatePayoutRequest.class))).thenAnswer(inv -> {
            CreatePayoutRequest req = inv.getArgument(0);
            WorkerPayoutEntity p = payout(req.amount(), PayoutStatus.PLANNED);
            p.setWorker(users.get(req.workerId()));
            p.setPaymentChannel(req.paymentChannel());
            p.setPayoutDate(req.payoutDate());
            p.setPeriodFrom(req.periodFrom());
            p.setPeriodTo(req.periodTo());
            p.setNote(req.note());
            return new WorkerPayoutDto(p.getId(), req.workerId(), null, req.payoutDate(), req.periodFrom(),
                    req.periodTo(), req.amount(), req.amount(), BigDecimal.ZERO, req.paymentChannel(),
                    PayoutStatus.PLANNED, false, null, req.note(), List.of());
        });

        // Payment seam: a fresh CONFIRMED payment transaction per call.
        when(paymentTransactionService.recordBankTransfer(anyLong(), any())).thenAnswer(inv -> {
            transferSchedules.add(inv.getArgument(0));
            transfers.add(inv.getArgument(1));
            return ids.incrementAndGet();
        });
        when(paymentTransactionDao.findById(anyLong())).thenAnswer(inv -> {
            PaymentTransactionEntity pt = new PaymentTransactionEntity();
            pt.setId(inv.getArgument(0));
            return Optional.of(pt);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends com.foremen.dao.model.BaseEntity> void byId(
            com.foremen.dao.AdminDao dao, Map<Long, E> store) {
        when(dao.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<Long>getArgument(0))));
    }

    // --- builders --------------------------------------------------------------------------------

    BankAccountEntity account(BankAccountType type) {
        BankAccountEntity a = new BankAccountEntity();
        a.setId(ids.incrementAndGet());
        a.setType(type);
        a.setName(type == BankAccountType.CASH ? "Kasa PLN" : "Firma PKO");
        a.setCurrency("PLN");
        accounts.put(a.getId(), a);
        return a;
    }

    /** A POSTED bank-feed line with a counterparty key. */
    BankTransactionEntity bankLine(String signedAmount) {
        BankTransactionEntity t = line(LedgerChannel.BANK, LedgerOrigin.BANK_FEED, signedAmount);
        t.setExternalId("ext-" + t.getId());
        t.setCounterpartyName("Jan Kowalski");
        t.setCounterpartyKey("IBAN:PL61109010140000071219812874");
        return t;
    }

    /** A POSTED manual cash line. */
    BankTransactionEntity cashLine(String signedAmount) {
        return line(LedgerChannel.CASH, LedgerOrigin.MANUAL, signedAmount);
    }

    private BankTransactionEntity line(LedgerChannel channel, LedgerOrigin origin, String signedAmount) {
        BankTransactionEntity t = new BankTransactionEntity();
        t.setId(ids.incrementAndGet());
        t.setAccount(account(channel == LedgerChannel.CASH ? BankAccountType.CASH : BankAccountType.BANK));
        t.setChannel(channel);
        t.setOrigin(origin);
        t.setStatus(BankTxnStatus.POSTED);
        t.setAmount(new BigDecimal(signedAmount));
        t.setCurrency("PLN");
        t.setOperationDate(BOOKING);
        t.setBookingDate(BOOKING);
        t.setTitle("Przelew " + t.getId());
        txns.put(t.getId(), t);
        return t;
    }

    UserEntity worker() {
        UserEntity u = new UserEntity();
        u.setId(ids.incrementAndGet());
        u.setName("Worker " + u.getId());
        users.put(u.getId(), u);
        return u;
    }

    WorkerPayoutEntity payout(BigDecimal amount, PayoutStatus status) {
        WorkerPayoutEntity p = new WorkerPayoutEntity();
        p.setId(ids.incrementAndGet());
        p.setAmount(amount);
        p.setStatus(status);
        p.setPaymentChannel(PaymentChannel.BANK_TRANSFER);
        p.setWorker(worker());
        payouts.put(p.getId(), p);
        return p;
    }

    ProjectEntity project() {
        ProjectEntity p = new ProjectEntity();
        p.setId(ids.incrementAndGet());
        p.setName("Project " + p.getId());
        projects.put(p.getId(), p);
        return p;
    }

    ProcurementDocumentEntity document(BigDecimal total) {
        ProcurementDocumentEntity d = new ProcurementDocumentEntity();
        d.setId(ids.incrementAndGet());
        d.setDocumentTotal(total);
        d.setSupplierName("Castorama");
        d.setSupplierNip("5260250995");
        d.setProject(project());
        documents.put(d.getId(), d);
        return d;
    }

    PaymentScheduleEntity schedule() {
        PaymentScheduleEntity s = new PaymentScheduleEntity();
        s.setId(ids.incrementAndGet());
        s.setProject(project());
        schedules.put(s.getId(), s);
        return s;
    }

    UnplannedExpenseCategoryEntity category() {
        UnplannedExpenseCategoryEntity c = new UnplannedExpenseCategoryEntity();
        c.setId(ids.incrementAndGet());
        c.setCode("FUEL");
        categories.put(c.getId(), c);
        return c;
    }

    BankTransactionSuggestionEntity openSuggestion(BankTransactionEntity txn, LinkRequest target) {
        return service.appendSuggestions(txn.getId(),
                List.of(new SuggestionDraft(target, com.foremen.dao.model.DetectionSource.RULE,
                        new BigDecimal("0.7"), "amount"))).get(0);
    }

    List<BankTransactionLinkEntity> linksOf(BankTransactionEntity txn) {
        return links.stream().filter(l -> l.getTransaction().getId().equals(txn.getId())).toList();
    }

    BigDecimal linkedOnLine(BankTransactionEntity txn) {
        return linksOf(txn).stream().map(BankTransactionLinkEntity::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    long openSuggestions(BankTransactionEntity txn) {
        return suggestions.stream().filter(s -> s.getTransaction() == txn && s.getStatus() == SuggestionStatus.OPEN)
                .count();
    }
}
