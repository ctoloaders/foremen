package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankCounterpartyRuleDao;
import com.foremen.dao.BankLinkedAmount;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankCounterpartyRuleEntity;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.service.bank.detect.MatchCandidate.TargetType;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.MatchInputs.OwnAccount;
import com.foremen.service.bank.detect.MatchInputs.PayoutCandidate;
import com.foremen.service.bank.detect.MatchInputs.ScheduleCandidate;
import com.foremen.service.bank.link.DocumentPaymentStatusDeriver;
import com.foremen.service.payment.ScheduleStatus;

/**
 * FOR-07-04 (Requirements 14.2–14.6, 15.2; design "Candidate providers") — the DB reads feeding the
 * pure {@link RuleMatcher}:
 *
 * <table>
 *   <caption>Providers</caption>
 *   <tr><th>Provider</th><th>Candidates</th></tr>
 *   <tr><td>{@link #ownAccounts()}</td><td>own BANK accounts with an IBAN → INTERNAL_TRANSFER</td></tr>
 *   <tr><td>{@link #counterpartyRules(String)}</td><td>the enabled rule of a counterparty key</td></tr>
 *   <tr><td>{@link #clientSchedules()}</td><td>published schedules, one entry per project CLIENT member
 *       (name / NIP) with the open tranche remainders; {@code FM-{id}} references are resolved by the
 *       matcher against these</td></tr>
 *   <tr><td>{@link #payouts(LocalDate, LocalDate)}</td><td>{@code BANK_TRANSFER} payouts, {@code PLANNED}
 *       and {@code PAID} not fully linked</td></tr>
 *   <tr><td>{@link #procurementDocuments(LocalDate, LocalDate)}</td><td>documents in
 *       {@code UNMATCHED} / {@code PARTIALLY_PAID}</td></tr>
 *   <tr><td>{@link #aiCatalog()}</td><td>active categories + active projects (AI only)</td></tr>
 * </table>
 *
 * <p>{@link #load(Collection)} reads everything once for a batch of lines (the union of their date
 * windows); {@link CandidatePool#inputsFor} narrows it per line.
 */
@Component
public class DetectionCandidateProviders {

    static final String CLIENT_ROLE = "CLIENT";
    /** Project statuses offered to the AI classifier as "active projects". */
    static final Set<ProjectStatus> ACTIVE_PROJECT_STATUSES =
            Set.of(ProjectStatus.APPROVED, ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD);
    private static final Pattern POSTAL_CITY = Pattern.compile("\\b\\d{2}-\\d{3}\\s+([^,]+)");

    private final BankAccountDao accountDao;
    private final BankCounterpartyRuleDao ruleDao;
    private final PaymentScheduleDao scheduleDao;
    private final ProjectMemberDao memberDao;
    private final WorkerPayoutDao payoutDao;
    private final ProcurementDocumentDao documentDao;
    private final BankTransactionLinkDao linkDao;
    private final UnplannedExpenseCategoryDao categoryDao;
    private final ProjectDao projectDao;

    @SuppressWarnings("java:S107")
    public DetectionCandidateProviders(BankAccountDao accountDao, BankCounterpartyRuleDao ruleDao,
                                       PaymentScheduleDao scheduleDao, ProjectMemberDao memberDao,
                                       WorkerPayoutDao payoutDao, ProcurementDocumentDao documentDao,
                                       BankTransactionLinkDao linkDao, UnplannedExpenseCategoryDao categoryDao,
                                       ProjectDao projectDao) {
        this.accountDao = accountDao;
        this.ruleDao = ruleDao;
        this.scheduleDao = scheduleDao;
        this.memberDao = memberDao;
        this.payoutDao = payoutDao;
        this.documentDao = documentDao;
        this.linkDao = linkDao;
        this.categoryDao = categoryDao;
        this.projectDao = projectDao;
    }

    /** The AI catalog: active categories (+ code → id) and active projects (R15.2). */
    public record AiCatalog(List<ClassificationInput.Category> categories, Map<String, Long> categoryIds,
                            List<ClassificationInput.Project> projects) {

        public AiCatalog {
            categories = categories == null ? List.of() : List.copyOf(categories);
            categoryIds = categoryIds == null ? Map.of() : Map.copyOf(categoryIds);
            projects = projects == null ? List.of() : List.copyOf(projects);
        }

        public static AiCatalog empty() {
            return new AiCatalog(null, null, null);
        }
    }

    /**
     * Loads the candidate pool for a batch of lines: schedules only when an INCOME line is present,
     * payouts / documents only when an EXPENSE line is present, over the union of the line windows.
     */
    @Transactional(readOnly = true)
    public CandidatePool load(Collection<TxnFacts> lines) {
        if (lines == null || lines.isEmpty()) {
            return CandidatePool.empty();
        }
        Map<String, CandidatePool.Label> labels = new HashMap<>();
        List<OwnAccount> own = ownAccounts(labels);
        List<ScheduleCandidate> schedules = List.of();
        if (lines.stream().anyMatch(t -> t.direction() == TxnFacts.Direction.INCOME)) {
            schedules = clientSchedules(labels);
        }
        List<PayoutCandidate> payouts = List.of();
        List<DocumentCandidate> documents = List.of();
        List<LocalDate> expenseDates = lines.stream()
                .filter(t -> t.direction() == TxnFacts.Direction.EXPENSE)
                .map(TxnFacts::bookingDate).sorted().toList();
        if (!expenseDates.isEmpty()) {
            LocalDate min = expenseDates.get(0);
            LocalDate max = expenseDates.get(expenseDates.size() - 1);
            payouts = payouts(min.minusDays(CandidatePool.PAYOUT_WINDOW_DAYS),
                    max.plusDays(CandidatePool.PAYOUT_WINDOW_DAYS), labels);
            documents = procurementDocuments(min.minusDays(CandidatePool.DOC_DAYS_BEFORE),
                    max.plusDays(CandidatePool.DOC_DAYS_AFTER), labels);
        }
        return new CandidatePool(own, schedules, payouts, documents, labels);
    }

    // ---------------------------------------------------------------- own account (R14.2)

    @Transactional(readOnly = true)
    public List<OwnAccount> ownAccounts() {
        return ownAccounts(new HashMap<>());
    }

    private List<OwnAccount> ownAccounts(Map<String, CandidatePool.Label> labels) {
        List<OwnAccount> out = new ArrayList<>();
        for (BankAccountEntity a : accountDao.findByTypeOrderByIdAsc(BankAccountType.BANK)) {
            if (a.getIban() == null || a.getIban().isBlank()) {
                continue;
            }
            out.add(new OwnAccount(a.getId(), a.getIban()));
            labels.put(CandidatePool.labelKey(TargetType.OWN_ACCOUNT, a.getId()),
                    new CandidatePool.Label(a.getName(), null, null));
        }
        return out;
    }

    // ---------------------------------------------------------------- counterparty rule (R14.3)

    /** The enabled rule of the counterparty key (0 or 1 element). */
    @Transactional(readOnly = true)
    public List<CounterpartyRule> counterpartyRules(String counterpartyKey) {
        if (counterpartyKey == null || counterpartyKey.isBlank()) {
            return List.of();
        }
        return ruleDao.findByCounterpartyKeyAndEnabledTrue(counterpartyKey)
                .map(DetectionCandidateProviders::toRule)
                .map(List::of)
                .orElse(List.of());
    }

    static CounterpartyRule toRule(BankCounterpartyRuleEntity r) {
        return new CounterpartyRule(r.getCounterpartyKey(), r.getTargetKind(),
                r.getWorker() == null ? null : r.getWorker().getId(),
                r.getSupplierName(), r.getSupplierNip(),
                r.getProject() == null ? null : r.getProject().getId(),
                r.getPaymentSchedule() == null ? null : r.getPaymentSchedule().getId(),
                r.getUnplannedCategory() == null ? null : r.getUnplannedCategory().getId(),
                r.getCounterAccount() == null ? null : r.getCounterAccount().getId(),
                r.isEnabled());
    }

    // ---------------------------------------------------------------- client schedule (R14.4)

    @Transactional(readOnly = true)
    public List<ScheduleCandidate> clientSchedules() {
        return clientSchedules(new HashMap<>());
    }

    private List<ScheduleCandidate> clientSchedules(Map<String, CandidatePool.Label> labels) {
        List<ScheduleCandidate> out = new ArrayList<>();
        for (PaymentScheduleEntity s : scheduleDao.findByStatus(ScheduleStatus.PUBLISHED)) {
            ProjectEntity project = s.getProject();
            if (project == null) {
                continue;
            }
            List<BigDecimal> remainders = openRemainders(s);
            List<ProjectMemberEntity> clients = memberDao.findByProjectIdWithUserAndRole(project.getId()).stream()
                    .filter(m -> m.getUser() != null && m.getProjectRole() != null
                            && CLIENT_ROLE.equals(m.getProjectRole().getCode()))
                    .toList();
            if (clients.isEmpty()) {
                out.add(new ScheduleCandidate(s.getId(), project.getId(), true, null, null, remainders));
            }
            for (ProjectMemberEntity m : clients) {
                out.add(new ScheduleCandidate(s.getId(), project.getId(), true, m.getUser().getName(),
                        m.getUser().getNip(), remainders));
            }
            labels.put(CandidatePool.labelKey(TargetType.PAYMENT_SCHEDULE, s.getId()),
                    new CandidatePool.Label(project.getName(),
                            remainders.isEmpty() ? null : remainders.get(0), null));
        }
        return out;
    }

    /** {@code amountGross − paidGross} of every tranche with something left to pay. */
    static List<BigDecimal> openRemainders(PaymentScheduleEntity s) {
        List<BigDecimal> out = new ArrayList<>();
        for (PaymentTrancheEntity t : s.getTranches()) {
            BigDecimal remainder = nz(t.getAmountGross()).subtract(nz(t.getPaidGross())).setScale(2, RoundingMode.HALF_UP);
            if (remainder.signum() > 0) {
                out.add(remainder);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- payout (R14.5)

    @Transactional(readOnly = true)
    public List<PayoutCandidate> payouts(LocalDate from, LocalDate to) {
        return payouts(from, to, new HashMap<>());
    }

    private List<PayoutCandidate> payouts(LocalDate from, LocalDate to, Map<String, CandidatePool.Label> labels) {
        List<WorkerPayoutEntity> rows = payoutDao.findLedgerCandidates(PaymentChannel.BANK_TRANSFER,
                List.of(PayoutStatus.PLANNED, PayoutStatus.PAID), from, to);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> linked = sums(linkDao.sumAmountByWorkerPayoutIds(
                rows.stream().map(WorkerPayoutEntity::getId).toList()));
        List<PayoutCandidate> out = new ArrayList<>();
        for (WorkerPayoutEntity p : rows) {
            boolean fullyLinked = p.getAmount() != null
                    && linked.getOrDefault(p.getId(), BigDecimal.ZERO).compareTo(p.getAmount()) >= 0;
            if (p.getStatus() == PayoutStatus.PAID && fullyLinked) {
                continue;
            }
            String workerName = p.getWorker() == null ? null : p.getWorker().getName();
            out.add(new PayoutCandidate(p.getId(), p.getWorker() == null ? 0L : p.getWorker().getId(), workerName,
                    p.getAmount(), p.getPayoutDate(), p.getStatus(), p.getPaymentChannel(), fullyLinked));
            labels.put(CandidatePool.labelKey(TargetType.WORKER_PAYOUT, p.getId()),
                    new CandidatePool.Label(workerName, p.getAmount(), p.getPayoutDate()));
        }
        return out;
    }

    // ---------------------------------------------------------------- procurement (R14.6)

    @Transactional(readOnly = true)
    public List<DocumentCandidate> procurementDocuments(LocalDate from, LocalDate to) {
        return procurementDocuments(from, to, new HashMap<>());
    }

    private List<DocumentCandidate> procurementDocuments(LocalDate from, LocalDate to,
                                                         Map<String, CandidatePool.Label> labels) {
        List<ProcurementDocumentEntity> rows = documentDao.findLedgerCandidates(from, to,
                from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).minusNanos(1));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> linked = sums(linkDao.sumAmountByProcurementDocumentIds(
                rows.stream().map(ProcurementDocumentEntity::getId).toList()));
        List<DocumentCandidate> out = new ArrayList<>();
        for (ProcurementDocumentEntity d : rows) {
            DocumentCandidate c = toDocumentCandidate(d, linked.get(d.getId()));
            if (c.paymentStatus() == DocumentPaymentStatus.PAID) {
                continue;
            }
            out.add(c);
            labels.put(CandidatePool.labelKey(TargetType.PROCUREMENT_DOCUMENT, d.getId()), documentLabel(d));
        }
        return out;
    }

    /** A document as a matcher candidate, its payment status derived from Σ links (2.9). */
    public static DocumentCandidate toDocumentCandidate(ProcurementDocumentEntity d, BigDecimal linkedSum) {
        List<BigDecimal> amounts = linkedSum == null || linkedSum.signum() <= 0 ? List.of() : List.of(linkedSum);
        DocumentPaymentStatus status = DocumentPaymentStatusDeriver.derive(d.getDocumentTotal(), amounts);
        OffsetDateTime uploaded = d.getUploadedAt();
        return new DocumentCandidate(d.getId(), d.getDocumentTotal(), d.getInvoiceNumber(), d.getSupplierName(),
                d.getSupplierNip(), d.getInvoiceDate(),
                uploaded == null ? null : uploaded.atZoneSameInstant(ZoneOffset.UTC).toLocalDate(), status);
    }

    static CandidatePool.Label documentLabel(ProcurementDocumentEntity d) {
        String supplier = d.getSupplierName() == null ? "?" : d.getSupplierName();
        String label = d.getInvoiceNumber() == null ? supplier : supplier + " " + d.getInvoiceNumber();
        return new CandidatePool.Label(label, d.getDocumentTotal(), d.getInvoiceDate());
    }

    // ---------------------------------------------------------------- category / project (R15.2)

    @Transactional(readOnly = true)
    public AiCatalog aiCatalog() {
        List<ClassificationInput.Category> categories = new ArrayList<>();
        Map<String, Long> ids = new LinkedHashMap<>();
        List<UnplannedExpenseCategoryEntity> all = StreamSupport.stream(categoryDao.findAll().spliterator(), false)
                .filter(UnplannedExpenseCategoryEntity::isActive)
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        for (UnplannedExpenseCategoryEntity c : all) {
            categories.add(new ClassificationInput.Category(c.getCode(), c.getNamePL(), c.getNameRU()));
            ids.put(c.getCode(), c.getId());
        }
        List<ClassificationInput.Project> projects = projectDao.findByStatusInOrderByIdAsc(ACTIVE_PROJECT_STATUSES)
                .stream()
                .map(p -> new ClassificationInput.Project(p.getId(), p.getName(),
                        city(p.getFormattedAddress() != null ? p.getFormattedAddress() : p.getAddress())))
                .toList();
        return new AiCatalog(categories, ids, projects);
    }

    /** The city of a Polish address ({@code "…, 00-001 Warszawa, …"}), or {@code null}. */
    static String city(String address) {
        if (address == null) {
            return null;
        }
        Matcher m = POSTAL_CITY.matcher(address);
        return m.find() ? m.group(1).strip() : null;
    }

    // ---------------------------------------------------------------- helpers

    private static Map<Long, BigDecimal> sums(List<BankLinkedAmount> rows) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (BankLinkedAmount r : rows) {
            out.put(r.targetId(), nz(r.total()));
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
