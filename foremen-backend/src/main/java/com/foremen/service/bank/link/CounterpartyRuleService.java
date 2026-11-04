package com.foremen.service.bank.link;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankCounterpartyRuleDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.BankCounterpartyRuleEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.RuleOrigin;
import com.foremen.exception.ForemenApiException;

/**
 * FOR-07-04 (Requirements 17.3, 17.4; design §Links and effects — rule learning) — counterparty rules:
 *
 * <ul>
 *   <li>{@link #learn} upserts the rule of a {@code BANK_FEED} line's counterparty key from a confirmed /
 *       created link (target per kind: worker, supplier name / NIP, schedule + project, category +
 *       project, counter account), increments {@code hits} and sets {@code last_used_at}. Cash lines
 *       ({@code MANUAL} / {@code SYSTEM}) never learn.</li>
 *   <li>CRUD for the "Counterparty rules" tab: list, get, create ({@code MANUAL} origin), edit target,
 *       enable / disable, delete. Every write is audited.</li>
 * </ul>
 */
@Service
public class CounterpartyRuleService {

    /** 409 — another rule already exists for the counterparty key. */
    public static final String DUPLICATE_KEY = "error.bank.rule.duplicateKey";
    /** 400 — the counterparty key / target kind is missing. */
    public static final String KEY_REQUIRED = "error.bank.rule.keyRequired";

    private final BankCounterpartyRuleDao ruleDao;
    private final UserDao userDao;
    private final ProjectDao projectDao;
    private final PaymentScheduleDao paymentScheduleDao;
    private final UnplannedExpenseCategoryDao categoryDao;
    private final BankAccountDao bankAccountDao;
    private final BankLedgerAudit audit;

    public CounterpartyRuleService(BankCounterpartyRuleDao ruleDao, UserDao userDao, ProjectDao projectDao,
                                   PaymentScheduleDao paymentScheduleDao,
                                   UnplannedExpenseCategoryDao categoryDao, BankAccountDao bankAccountDao,
                                   BankLedgerAudit audit) {
        this.ruleDao = ruleDao;
        this.userDao = userDao;
        this.projectDao = projectDao;
        this.paymentScheduleDao = paymentScheduleDao;
        this.categoryDao = categoryDao;
        this.bankAccountDao = bankAccountDao;
        this.audit = audit;
    }

    // --- Learning (R17.3) -----------------------------------------------------------------------

    /**
     * Upserts the rule for the line's counterparty key from an applied link. No-op for cash lines, lines
     * without a counterparty key, and links that carry no learnable target.
     *
     * @return the upserted rule, empty when nothing was learned
     */
    @Transactional
    public Optional<BankCounterpartyRuleEntity> learn(BankTransactionEntity txn, BankTransactionLinkEntity link) {
        if (txn.getOrigin() != LedgerOrigin.BANK_FEED
                || txn.getCounterpartyKey() == null || txn.getCounterpartyKey().isBlank()) {
            return Optional.empty();
        }
        if (!learnable(link)) {
            return Optional.empty();
        }
        Optional<BankCounterpartyRuleEntity> existing = ruleDao.findByCounterpartyKey(txn.getCounterpartyKey());
        BankCounterpartyRuleEntity rule = existing.orElseGet(() -> {
            BankCounterpartyRuleEntity r = new BankCounterpartyRuleEntity();
            r.setCounterpartyKey(txn.getCounterpartyKey());
            r.setOrigin(RuleOrigin.LEARNED);
            r.setEnabled(true);
            return r;
        });
        Map<String, Object> before = existing.isPresent() ? snapshot(rule) : null;

        clearTargets(rule);
        rule.setTargetKind(link.getKind());
        switch (link.getKind()) {
            case WORKER_PAYOUT -> rule.setWorker(link.getWorkerPayout().getWorker());
            case PROCUREMENT -> {
                ProcurementDocumentEntity doc = link.getProcurementDocument();
                rule.setSupplierName(doc.getSupplierName());
                rule.setSupplierNip(doc.getSupplierNip());
            }
            case CLIENT_PAYMENT -> {
                rule.setPaymentSchedule(link.getPaymentSchedule());
                rule.setProject(link.getPaymentSchedule().getProject());
            }
            case OTHER_EXPENSE, OTHER_INCOME -> {
                rule.setUnplannedCategory(link.getUnplannedCategory());
                rule.setProject(link.getProject());
            }
            case INTERNAL_TRANSFER -> rule.setCounterAccount(link.getCounterAccount());
        }
        if (txn.getCounterpartyName() != null && !txn.getCounterpartyName().isBlank()) {
            rule.setDisplayName(txn.getCounterpartyName());
        }
        rule.setHits(rule.getHits() + 1);
        rule.setLastUsedAt(OffsetDateTime.now());
        BankCounterpartyRuleEntity saved = ruleDao.save(rule);
        audit.write(BankCounterpartyRuleEntity.class, saved.getId(), before == null ? "CREATE" : "UPDATE",
                before, snapshot(saved));
        return Optional.of(saved);
    }

    private static boolean learnable(BankTransactionLinkEntity link) {
        if (link == null || link.getKind() == null) {
            return false;
        }
        return switch (link.getKind()) {
            case WORKER_PAYOUT -> link.getWorkerPayout() != null && link.getWorkerPayout().getWorker() != null;
            case PROCUREMENT -> link.getProcurementDocument() != null
                    && (link.getProcurementDocument().getSupplierName() != null
                    || link.getProcurementDocument().getSupplierNip() != null);
            case CLIENT_PAYMENT -> link.getPaymentSchedule() != null;
            case OTHER_EXPENSE -> link.getUnplannedCategory() != null;
            case OTHER_INCOME, INTERNAL_TRANSFER -> true;
        };
    }

    // --- CRUD (R17.4) ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<CounterpartyRuleDto> list(Pageable pageable) {
        return ruleDao.findAll(pageable).map(CounterpartyRuleDto::of);
    }

    @Transactional(readOnly = true)
    public CounterpartyRuleDto get(Long id) {
        return CounterpartyRuleDto.of(find(id));
    }

    /** Creates a {@code MANUAL} rule; the key must be unique. */
    @Transactional
    public CounterpartyRuleDto create(CounterpartyRuleRequest req) {
        if (req == null || req.counterpartyKey() == null || req.counterpartyKey().isBlank()
                || req.targetKind() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, KEY_REQUIRED);
        }
        String key = req.counterpartyKey().trim();
        if (ruleDao.findByCounterpartyKey(key).isPresent()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DUPLICATE_KEY);
        }
        BankCounterpartyRuleEntity rule = new BankCounterpartyRuleEntity();
        rule.setCounterpartyKey(key);
        rule.setOrigin(RuleOrigin.MANUAL);
        rule.setEnabled(req.enabled() == null || req.enabled());
        applyTargets(rule, req);
        rule.setDisplayName(req.displayName());
        BankCounterpartyRuleEntity saved = ruleDao.save(rule);
        audit.write(BankCounterpartyRuleEntity.class, saved.getId(), "CREATE", null, snapshot(saved));
        return CounterpartyRuleDto.of(saved);
    }

    /** Edits the target / display name / enabled flag; the key is the rule identity and is kept. */
    @Transactional
    public CounterpartyRuleDto update(Long id, CounterpartyRuleRequest req) {
        BankCounterpartyRuleEntity rule = find(id);
        Map<String, Object> before = snapshot(rule);
        if (req.targetKind() != null) {
            applyTargets(rule, req);
        }
        if (req.displayName() != null) {
            rule.setDisplayName(req.displayName());
        }
        if (req.enabled() != null) {
            rule.setEnabled(req.enabled());
        }
        BankCounterpartyRuleEntity saved = ruleDao.save(rule);
        audit.write(BankCounterpartyRuleEntity.class, saved.getId(), "UPDATE", before, snapshot(saved));
        return CounterpartyRuleDto.of(saved);
    }

    @Transactional
    public void delete(Long id) {
        BankCounterpartyRuleEntity rule = find(id);
        Map<String, Object> before = snapshot(rule);
        ruleDao.delete(rule);
        audit.write(BankCounterpartyRuleEntity.class, id, "DELETE", before, null);
    }

    private BankCounterpartyRuleEntity find(Long id) {
        return ruleDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, BankLinkService.NOT_FOUND));
    }

    private void applyTargets(BankCounterpartyRuleEntity rule, CounterpartyRuleRequest req) {
        LinkKind kind = req.targetKind();
        clearTargets(rule);
        rule.setTargetKind(kind);
        switch (kind) {
            case WORKER_PAYOUT -> rule.setWorker(require(req.workerId() == null ? null
                    : userDao.findById(req.workerId()).orElseThrow(BankLinkService::notFound)));
            case PROCUREMENT -> {
                if (blank(req.supplierName()) && blank(req.supplierNip())) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.TARGET_REQUIRED);
                }
                rule.setSupplierName(blank(req.supplierName()) ? null : req.supplierName().trim());
                rule.setSupplierNip(blank(req.supplierNip()) ? null : req.supplierNip().trim());
            }
            case CLIENT_PAYMENT -> {
                PaymentScheduleEntity schedule = require(req.paymentScheduleId() == null ? null
                        : paymentScheduleDao.findById(req.paymentScheduleId()).orElseThrow(BankLinkService::notFound));
                rule.setPaymentSchedule(schedule);
                rule.setProject(schedule.getProject());
            }
            case OTHER_EXPENSE, OTHER_INCOME -> {
                if (kind == LinkKind.OTHER_EXPENSE && req.unplannedCategoryId() == null) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.TARGET_REQUIRED);
                }
                if (req.unplannedCategoryId() != null) {
                    rule.setUnplannedCategory(categoryDao.findById(req.unplannedCategoryId())
                            .orElseThrow(BankLinkService::notFound));
                }
                if (req.projectId() != null) {
                    rule.setProject(projectDao.findById(req.projectId()).orElseThrow(BankLinkService::notFound));
                }
            }
            case INTERNAL_TRANSFER -> {
                if (req.counterAccountId() != null) {
                    rule.setCounterAccount(bankAccountDao.findById(req.counterAccountId())
                            .orElseThrow(BankLinkService::notFound));
                }
            }
        }
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BankLinkService.TARGET_REQUIRED);
        }
        return value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void clearTargets(BankCounterpartyRuleEntity rule) {
        rule.setWorker(null);
        rule.setSupplierName(null);
        rule.setSupplierNip(null);
        rule.setProject(null);
        rule.setPaymentSchedule(null);
        rule.setUnplannedCategory(null);
        rule.setCounterAccount(null);
    }

    private static Map<String, Object> snapshot(BankCounterpartyRuleEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("counterpartyKey", r.getCounterpartyKey());
        m.put("targetKind", r.getTargetKind() == null ? null : r.getTargetKind().name());
        m.put("workerId", r.getWorker() == null ? null : r.getWorker().getId());
        m.put("supplierName", r.getSupplierName());
        m.put("supplierNip", r.getSupplierNip());
        m.put("projectId", r.getProject() == null ? null : r.getProject().getId());
        m.put("paymentScheduleId", r.getPaymentSchedule() == null ? null : r.getPaymentSchedule().getId());
        m.put("unplannedCategoryId", r.getUnplannedCategory() == null ? null : r.getUnplannedCategory().getId());
        m.put("counterAccountId", r.getCounterAccount() == null ? null : r.getCounterAccount().getId());
        m.put("enabled", r.isEnabled());
        m.put("hits", r.getHits());
        m.put("origin", r.getOrigin() == null ? null : r.getOrigin().name());
        return m;
    }
}
