package com.foremen.service.bank.link;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankAccountDao;
import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.WorkerPayoutDao;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.detect.ClassificationInput;
import com.foremen.service.bank.detect.ClassifierAnswerValidator;
import com.foremen.service.bank.detect.MatchCandidate;
import com.foremen.service.payroll.WorkerPayoutService;
import com.foremen.service.payroll.dto.CreatePayoutRequest;
import com.foremen.service.payroll.dto.WorkerPayoutDto;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-04 (Requirements 13, 16, 17; design §Links and effects) — every link / suggestion / ignore
 * mutation of a ledger line (bank or cash). Each public method is one database transaction that
 * validates, writes the link, runs {@link LinkEffects}, audits, learns the counterparty rule (BANK_FEED
 * only) and refreshes the {@code link_status} cache through {@link LinkStatusDeriver}.
 *
 * <p>Validation order for a new link (domain errors, never the deriver's {@code IllegalArgumentException}):
 * <ol>
 *   <li>line linkable: not ignored ({@link #TRANSACTION_IGNORED}), {@code POSTED} ({@link #NOT_POSTED});</li>
 *   <li>kind present and allowed for the direction ({@link #KIND_DIRECTION}, R13.2);</li>
 *   <li>required target present ({@link #TARGET_REQUIRED}) and existing ({@link #NOT_FOUND}) (R13.3);</li>
 *   <li>amount {@code > 0} ({@link #AMOUNT_INVALID});</li>
 *   <li>Σ line links ≤ |amount| ({@link #OVER_ALLOCATED}, R13.4);</li>
 *   <li>{@code PLANNED} payout: amount = payout amount ({@link #PAYOUT_AMOUNT_MISMATCH}, R16.1);</li>
 *   <li>Σ payout links ≤ payout amount, Σ document links ≤ known document total
 *       ({@link #TARGET_OVER_LINKED}, R13.6).</li>
 * </ol>
 *
 * <p><b>Locked</b> (R16.7, Property 9): a link is locked iff its {@code payment_transaction_id} is set,
 * i.e. its client payment was recorded and reconciled through FOR-06-07. A locked link is never replaced
 * or removed ({@link #LOCKED}); a line with a locked link therefore can neither be ignored (it has
 * links) nor have that link deleted with the line ({@link #hasLockedLinks} for the cash ledger).
 */
@Service
public class BankLinkService {

    private static final Logger log = LoggerFactory.getLogger(BankLinkService.class);

    static final String NOT_FOUND = "error.entity.not.found";
    /** 400 — the kind does not fit the line direction (R13.2). */
    public static final String KIND_DIRECTION = "error.bank.link.kindDirection";
    /** 400 — Σ link amounts would exceed |amount| (R13.4). */
    public static final String OVER_ALLOCATED = "error.bank.link.overAllocated";
    /** 409 — the payout / document would be linked beyond its amount (R13.6). */
    public static final String TARGET_OVER_LINKED = "error.bank.link.targetOverLinked";
    /** 409 — a PLANNED payout must be linked with exactly its amount (R16.1). */
    public static final String PAYOUT_AMOUNT_MISMATCH = "error.bank.link.payoutAmountMismatch";
    /** 409 — the link is locked by a reconciled client payment (R16.7). */
    public static final String LOCKED = "error.bank.link.locked";
    /** 400 — the kind or its required target is missing (R13.3). */
    public static final String TARGET_REQUIRED = "error.bank.link.targetRequired";
    /** 400 — the link amount is not positive. */
    public static final String AMOUNT_INVALID = "error.bank.link.amountInvalid";
    /** 409 — the line is ignored; un-ignore it first. */
    public static final String TRANSACTION_IGNORED = "error.bank.link.transactionIgnored";
    /** 409 — PENDING lines cannot be linked (they are replaced on the next fetch). */
    public static final String NOT_POSTED = "error.bank.link.notPosted";
    /** 409 — links must be removed before ignoring (R17.5). */
    public static final String IGNORE_HAS_LINKS = "error.bank.ignore.hasLinks";
    /** 400 — ignoring requires a reason (R17.5). */
    public static final String IGNORE_REASON_REQUIRED = "error.bank.ignore.reasonRequired";
    /** 409 — the suggestion is no longer OPEN. */
    public static final String SUGGESTION_NOT_OPEN = "error.bank.suggestion.notOpen";

    private static final int RATIONALE_MAX = 300;

    private final BankTransactionDao transactionDao;
    private final BankTransactionLinkDao linkDao;
    private final BankTransactionSuggestionDao suggestionDao;
    private final WorkerPayoutDao workerPayoutDao;
    private final ProcurementDocumentDao procurementDocumentDao;
    private final PaymentScheduleDao paymentScheduleDao;
    private final ProjectDao projectDao;
    private final UnplannedExpenseCategoryDao categoryDao;
    private final BankAccountDao bankAccountDao;
    private final WorkerPayoutService workerPayoutService;
    private final LinkEffects effects;
    private final CounterpartyRuleService ruleService;
    private final BankLedgerAudit audit;
    private final EntityManager entityManager;

    @SuppressWarnings("java:S107")
    public BankLinkService(BankTransactionDao transactionDao, BankTransactionLinkDao linkDao,
                           BankTransactionSuggestionDao suggestionDao, WorkerPayoutDao workerPayoutDao,
                           ProcurementDocumentDao procurementDocumentDao, PaymentScheduleDao paymentScheduleDao,
                           ProjectDao projectDao, UnplannedExpenseCategoryDao categoryDao,
                           BankAccountDao bankAccountDao, WorkerPayoutService workerPayoutService,
                           LinkEffects effects, CounterpartyRuleService ruleService, BankLedgerAudit audit,
                           EntityManager entityManager) {
        this.transactionDao = transactionDao;
        this.linkDao = linkDao;
        this.suggestionDao = suggestionDao;
        this.workerPayoutDao = workerPayoutDao;
        this.procurementDocumentDao = procurementDocumentDao;
        this.paymentScheduleDao = paymentScheduleDao;
        this.projectDao = projectDao;
        this.categoryDao = categoryDao;
        this.bankAccountDao = bankAccountDao;
        this.workerPayoutService = workerPayoutService;
        this.effects = effects;
        this.ruleService = ruleService;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ============================================================================================
    // Detection API (6.8)
    // ============================================================================================

    /**
     * Auto-applies a matcher candidate: a {@code RULE} link with {@code confidence = score} and
     * {@code confirmed_at = null}, plus its effects. Nothing is learned. When the candidate does not
     * validate against the current state (e.g. the target got linked meanwhile) nothing is written and
     * the result is empty, so detection can fall back to suggestions without failing its transaction.
     */
    @Transactional
    public Optional<LinkOutcome> applyAuto(Long transactionId, MatchCandidate candidate) {
        BankTransactionEntity txn = findTxn(transactionId);
        Resolved resolved;
        try {
            assertLinkable(txn);
            resolved = resolve(txn, LinkRequest.fromCandidate(candidate));
        } catch (ForemenApiException e) {
            log.info("Auto-link skipped for bank transaction {}: {}", transactionId, e.getMessage());
            return Optional.empty();
        }
        BankTransactionLinkEntity link = persist(txn, resolved, DetectionSource.RULE,
                confidence(candidate.score()), false);
        return Optional.of(new LinkOutcome(txn.getId(), link.getId(), refresh(txn), false));
    }

    /**
     * Replaces the line's {@code OPEN} suggestions ({@code SUPERSEDED}) with {@code drafts} (ranked in
     * order). Drafts that do not validate (direction, missing target) are skipped.
     *
     * @return the stored suggestions
     */
    @Transactional
    public List<BankTransactionSuggestionEntity> replaceSuggestions(Long transactionId, List<SuggestionDraft> drafts) {
        BankTransactionEntity txn = findTxn(transactionId);
        supersedeOpen(txn.getId(), null);
        List<BankTransactionSuggestionEntity> saved = storeSuggestions(txn, drafts, 1);
        refresh(txn);
        return saved;
    }

    /**
     * Appends {@code drafts} after the line's {@code OPEN} suggestions (e.g. the AI suggestion ranked
     * after the rule ones) without superseding them.
     */
    @Transactional
    public List<BankTransactionSuggestionEntity> appendSuggestions(Long transactionId, List<SuggestionDraft> drafts) {
        BankTransactionEntity txn = findTxn(transactionId);
        int nextRank = suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(txn.getId(), SuggestionStatus.OPEN)
                .stream().mapToInt(BankTransactionSuggestionEntity::getRank).max().orElse(0) + 1;
        List<BankTransactionSuggestionEntity> saved = storeSuggestions(txn, drafts, nextRank);
        refresh(txn);
        return saved;
    }

    /** Supersedes every {@code OPEN} suggestion of the line ({@code rerunDetection}). */
    @Transactional
    public LinkOutcome clearOpenSuggestions(Long transactionId) {
        BankTransactionEntity txn = findTxn(transactionId);
        supersedeOpen(txn.getId(), null);
        return new LinkOutcome(txn.getId(), null, refresh(txn), false);
    }

    // ============================================================================================
    // User operations (R17.1)
    // ============================================================================================

    /**
     * Confirms an {@code OPEN} suggestion: a link with the suggestion's {@code source} / confidence,
     * {@code confirmed_*} set; the suggestion {@code CONFIRMED}, the line's other open suggestions
     * {@code SUPERSEDED}; effects; rule learning when {@code remember} (BANK_FEED only).
     */
    @Transactional
    public LinkOutcome confirmSuggestion(Long suggestionId, boolean remember) {
        BankTransactionSuggestionEntity sug = suggestionDao.findById(suggestionId).orElseThrow(BankLinkService::notFound);
        if (sug.getStatus() != SuggestionStatus.OPEN) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SUGGESTION_NOT_OPEN);
        }
        BankTransactionEntity txn = sug.getTransaction();
        assertLinkable(txn);
        Resolved resolved = resolve(txn, toRequest(sug));
        BankTransactionLinkEntity link = persist(txn, resolved, sug.getSource(), sug.getConfidence(), true);
        sug.setStatus(SuggestionStatus.CONFIRMED);
        suggestionDao.save(sug);
        supersedeOpen(txn.getId(), sug.getId());
        learn(txn, link, remember);
        return new LinkOutcome(txn.getId(), link.getId(), refresh(txn), false);
    }

    /**
     * Confirms the unconfirmed automatic links of a line ({@code AUTO_LINKED → LINKED}) and marks the
     * line reviewed; learns the rule of each confirmed link when {@code remember} (BANK_FEED only).
     */
    @Transactional
    public LinkOutcome confirmAuto(Long transactionId, boolean remember) {
        BankTransactionEntity txn = findTxn(transactionId);
        String user = BankLedgerAudit.currentUser();
        OffsetDateTime now = OffsetDateTime.now();
        for (BankTransactionLinkEntity link : linkDao.findByTransactionIdOrderByIdAsc(txn.getId())) {
            if (link.getConfirmedAt() != null) {
                continue;
            }
            Map<String, Object> before = BankLedgerAudit.linkSnapshot(link);
            link.setConfirmedAt(now);
            link.setConfirmedBy(user);
            linkDao.save(link);
            audit.write(BankTransactionLinkEntity.class, link.getId(), "CONFIRM", before,
                    BankLedgerAudit.linkSnapshot(link));
            learn(txn, link, remember);
        }
        txn.setReviewedAt(now);
        txn.setReviewedBy(user);
        return new LinkOutcome(txn.getId(), null, refresh(txn), false);
    }

    /** {@code OPEN → REJECTED}; no side effects. */
    @Transactional
    public LinkOutcome rejectSuggestion(Long suggestionId) {
        BankTransactionSuggestionEntity sug = suggestionDao.findById(suggestionId).orElseThrow(BankLinkService::notFound);
        if (sug.getStatus() != SuggestionStatus.OPEN) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SUGGESTION_NOT_OPEN);
        }
        sug.setStatus(SuggestionStatus.REJECTED);
        suggestionDao.save(sug);
        audit.write(BankTransactionSuggestionEntity.class, sug.getId(), "REJECT", null,
                Map.of("transactionId", sug.getTransaction().getId(), "kind", sug.getKind().name()));
        BankTransactionEntity txn = sug.getTransaction();
        return new LinkOutcome(txn.getId(), null, refresh(txn), false);
    }

    /**
     * Creates a {@code MANUAL} (confirmed) link — also the entry point for cash lines (6.12: manual
     * entries, payout / procurement cash hooks). Effects run as for bank lines with the line's channel;
     * the rule is learned when {@code remember} and the line is {@code BANK_FEED}.
     */
    @Transactional
    public LinkOutcome createLink(Long transactionId, LinkRequest request, boolean remember) {
        BankTransactionEntity txn = findTxn(transactionId);
        assertLinkable(txn);
        Resolved resolved = resolve(txn, request);
        BankTransactionLinkEntity link = persist(txn, resolved, DetectionSource.MANUAL, null, true);
        learn(txn, link, remember);
        supersedeIfFullyAllocated(txn);
        return new LinkOutcome(txn.getId(), link.getId(), refresh(txn), false);
    }

    /**
     * Override (R17.1): rejects a locked link; otherwise removes it (no inverse effect — a paid payout
     * stays {@code PAID}) and creates the new {@code MANUAL} link in the same transaction. A failing new
     * link rolls the removal back.
     */
    @Transactional
    public LinkOutcome replaceLink(Long linkId, LinkRequest request, boolean remember) {
        BankTransactionLinkEntity old = linkDao.findById(linkId).orElseThrow(BankLinkService::notFound);
        assertUnlocked(old);
        BankTransactionEntity txn = old.getTransaction();
        assertLinkable(txn);
        Map<String, Object> before = BankLedgerAudit.linkSnapshot(old);
        boolean paidPayout = staysPaid(old);
        Long oldPayoutId = old.getWorkerPayout() == null ? null : old.getWorkerPayout().getId();
        // Validate the new link as if the old one were gone, before touching anything.
        resolve(txn, request, old);
        linkDao.delete(old);
        entityManager.flush();

        Resolved resolved = resolve(txn, request);
        BankTransactionLinkEntity link = persistWithoutAudit(txn, resolved, DetectionSource.MANUAL, null, true);
        audit.write(BankTransactionLinkEntity.class, link.getId(), "REPLACE", before,
                BankLedgerAudit.linkSnapshot(link));
        learn(txn, link, remember);
        supersedeIfFullyAllocated(txn);
        boolean warn = paidPayout && !(link.getWorkerPayout() != null
                && link.getWorkerPayout().getId().equals(oldPayoutId));
        return new LinkOutcome(txn.getId(), link.getId(), refresh(txn), warn);
    }

    /** Removes an unlocked link; a paid payout stays {@code PAID} (warning flag, R16.8). */
    @Transactional
    public LinkOutcome removeLink(Long linkId) {
        BankTransactionLinkEntity link = linkDao.findById(linkId).orElseThrow(BankLinkService::notFound);
        assertUnlocked(link);
        BankTransactionEntity txn = link.getTransaction();
        boolean warn = staysPaid(link);
        Map<String, Object> before = BankLedgerAudit.linkSnapshot(link);
        linkDao.delete(link);
        entityManager.flush();
        audit.write(BankTransactionLinkEntity.class, linkId, "DELETE", before, null);
        return new LinkOutcome(txn.getId(), null, refresh(txn), warn);
    }

    /** Marks the line {@code IGNORED} with a reason; it must carry no links (R17.5). Idempotent. */
    @Transactional
    public LinkOutcome ignore(Long transactionId, String reason) {
        BankTransactionEntity txn = findTxn(transactionId);
        if (reason == null || reason.isBlank()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, IGNORE_REASON_REQUIRED);
        }
        if (!linkDao.findByTransactionIdOrderByIdAsc(txn.getId()).isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, IGNORE_HAS_LINKS);
        }
        Map<String, Object> before = ignoreSnapshot(txn);
        txn.setIgnoredReason(reason.trim());
        txn.setIgnoredBy(BankLedgerAudit.currentUser());
        txn.setIgnoredAt(OffsetDateTime.now());
        audit.write(BankTransactionEntity.class, txn.getId(), "IGNORE", before, ignoreSnapshot(txn));
        return new LinkOutcome(txn.getId(), null, refresh(txn), false);
    }

    /** Clears the ignored mark. Idempotent. */
    @Transactional
    public LinkOutcome unignore(Long transactionId) {
        BankTransactionEntity txn = findTxn(transactionId);
        if (txn.getIgnoredReason() != null) {
            Map<String, Object> before = ignoreSnapshot(txn);
            txn.setIgnoredReason(null);
            txn.setIgnoredBy(null);
            txn.setIgnoredAt(null);
            audit.write(BankTransactionEntity.class, txn.getId(), "UNIGNORE", before, ignoreSnapshot(txn));
        }
        return new LinkOutcome(txn.getId(), null, refresh(txn), false);
    }

    /**
     * "Create payout from ledger line" (R16.2): creates a payout through the existing FOR-07-06 create
     * flow ({@code payoutDate = bookingDate}, period = the booking month, {@code amount = |link amount|},
     * channel = the line's channel, note {@code bank-txn:{id}}), marks it {@code PAID} from the ledger
     * and links it — all in one transaction.
     *
     * @param amount the payout / link amount; {@code null} → the line's unallocated remainder
     * @param note   the link note, may be {@code null}
     */
    @Transactional
    public LinkOutcome createPayoutFromLine(Long transactionId, Long workerId, BigDecimal amount, String note,
                                            boolean remember) {
        BankTransactionEntity txn = findTxn(transactionId);
        assertLinkable(txn);
        assertKindAllowed(txn, LinkKind.WORKER_PAYOUT);
        if (workerId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TARGET_REQUIRED);
        }
        BigDecimal remaining = lineRemainder(txn);
        BigDecimal value = amount == null ? remaining : scale(amount);
        assertAmount(value);
        if (value.compareTo(remaining) > 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, OVER_ALLOCATED);
        }
        LocalDate booking = txn.getBookingDate();
        WorkerPayoutDto payout = workerPayoutService.create(new CreatePayoutRequest(
                workerId, booking, booking.withDayOfMonth(1), booking.withDayOfMonth(booking.lengthOfMonth()),
                LinkEffects.payoutChannel(txn.getChannel()), value, "bank-txn:" + txn.getId()));
        LinkRequest req = LinkRequest.of(LinkKind.WORKER_PAYOUT, value).withWorkerPayout(payout.id()).withNote(note);
        Resolved resolved = resolve(txn, req);
        BankTransactionLinkEntity link = persist(txn, resolved, DetectionSource.MANUAL, null, true);
        learn(txn, link, remember);
        supersedeIfFullyAllocated(txn);
        return new LinkOutcome(txn.getId(), link.getId(), refresh(txn), false);
    }

    // ============================================================================================
    // Cash ledger helpers (6.12)
    // ============================================================================================

    /** Whether the line carries a locked link (cash entry edit / delete guard). */
    @Transactional(readOnly = true)
    public boolean hasLockedLinks(Long transactionId) {
        return linkDao.findByTransactionIdOrderByIdAsc(transactionId).stream().anyMatch(LinkEffects::isLocked);
    }

    /**
     * Removes every link of the line (cash entry delete / edit); rejects with {@link #LOCKED} when any is
     * locked. Payouts keep their status.
     */
    @Transactional
    public LinkOutcome removeAllLinks(Long transactionId) {
        BankTransactionEntity txn = findTxn(transactionId);
        List<BankTransactionLinkEntity> links = linkDao.findByTransactionIdOrderByIdAsc(txn.getId());
        for (BankTransactionLinkEntity link : links) {
            assertUnlocked(link);
        }
        boolean warn = false;
        for (BankTransactionLinkEntity link : links) {
            warn |= staysPaid(link);
            Map<String, Object> before = BankLedgerAudit.linkSnapshot(link);
            linkDao.delete(link);
            audit.write(BankTransactionLinkEntity.class, link.getId(), "DELETE", before, null);
        }
        entityManager.flush();
        return new LinkOutcome(txn.getId(), null, refresh(txn), warn);
    }

    /** Recomputes and stores the {@code link_status} cache of a line (e.g. after a cash entry edit). */
    @Transactional
    public LinkStatus refreshStatus(Long transactionId) {
        return refresh(findTxn(transactionId));
    }

    // ============================================================================================
    // Internals
    // ============================================================================================

    /** A validated link: kind, scaled amount and the resolved targets of the kind. */
    private record Resolved(LinkKind kind, BigDecimal amount, WorkerPayoutEntity payout,
                            ProcurementDocumentEntity document, PaymentScheduleEntity schedule,
                            ProjectEntity project, UnplannedExpenseCategoryEntity category,
                            BankAccountEntity counterAccount, String note) {
        Resolved withAmount(BigDecimal value) {
            return new Resolved(kind, value, payout, document, schedule, project, category, counterAccount, note);
        }
    }

    /** Kind, direction and the targets of the kind (R13.2, R13.3); the amount is left unset. */
    private Resolved resolveTargets(BankTransactionEntity txn, LinkRequest req) {
        if (req == null || req.kind() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TARGET_REQUIRED);
        }
        LinkKind kind = req.kind();
        assertKindAllowed(txn, kind);

        WorkerPayoutEntity payout = null;
        ProcurementDocumentEntity document = null;
        PaymentScheduleEntity schedule = null;
        ProjectEntity project = null;
        UnplannedExpenseCategoryEntity category = null;
        BankAccountEntity counterAccount = null;
        switch (kind) {
            case WORKER_PAYOUT -> payout = workerPayoutDao.findById(required(req.workerPayoutId()))
                    .orElseThrow(BankLinkService::notFound);
            case PROCUREMENT -> {
                document = procurementDocumentDao.findById(required(req.procurementDocumentId()))
                        .orElseThrow(BankLinkService::notFound);
                project = document.getProject();
            }
            case CLIENT_PAYMENT -> {
                schedule = paymentScheduleDao.findById(required(req.paymentScheduleId()))
                        .orElseThrow(BankLinkService::notFound);
                project = schedule.getProject();
            }
            case OTHER_EXPENSE -> {
                category = categoryDao.findById(required(req.unplannedCategoryId()))
                        .orElseThrow(BankLinkService::notFound);
                project = optionalProject(req.projectId());
            }
            case OTHER_INCOME -> project = optionalProject(req.projectId());
            case INTERNAL_TRANSFER -> {
                if (req.counterAccountId() != null) {
                    counterAccount = bankAccountDao.findById(req.counterAccountId())
                            .orElseThrow(BankLinkService::notFound);
                }
            }
        }
        return new Resolved(kind, null, payout, document, schedule, project, category, counterAccount, req.note());
    }

    /** Full link validation: targets, amount and the allocation bounds (Property 3). */
    private Resolved resolve(BankTransactionEntity txn, LinkRequest req) {
        return resolve(txn, req, null);
    }

    /**
     * Full link validation, as if {@code replaced} (a link of the same line being overridden) were
     * already gone: its amount is excluded from the line and target sums.
     */
    private Resolved resolve(BankTransactionEntity txn, LinkRequest req, BankTransactionLinkEntity replaced) {
        Resolved t = resolveTargets(txn, req);
        BigDecimal lineRemainder = lineRemainder(txn, replaced);
        WorkerPayoutEntity payout = t.payout();
        ProcurementDocumentEntity document = t.document();
        BigDecimal payoutLinked = payoutLinked(payout, replaced);
        BigDecimal documentLinked = documentLinked(document, replaced);
        BigDecimal amount = req.amount() != null
                ? scale(req.amount())
                : defaultAmount(lineRemainder, payout, payoutLinked, document, documentLinked);
        if (req.amount() == null && amount.signum() <= 0) {
            // The default is exhausted: either the line or the target has nothing left to link.
            throw lineRemainder.signum() <= 0
                    ? new ForemenApiException(HttpStatus.BAD_REQUEST, OVER_ALLOCATED)
                    : new ForemenApiException(HttpStatus.CONFLICT, TARGET_OVER_LINKED);
        }
        assertAmount(amount);
        if (amount.compareTo(lineRemainder) > 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, OVER_ALLOCATED);
        }
        if (payout != null) {
            if (payout.getStatus() == PayoutStatus.PLANNED && amount.compareTo(payout.getAmount()) != 0) {
                throw new ForemenApiException(HttpStatus.CONFLICT, PAYOUT_AMOUNT_MISMATCH);
            }
            if (payoutLinked.add(amount).compareTo(payout.getAmount()) > 0) {
                throw new ForemenApiException(HttpStatus.CONFLICT, TARGET_OVER_LINKED);
            }
        }
        if (document != null && document.getDocumentTotal() != null
                && documentLinked.add(amount).compareTo(document.getDocumentTotal()) > 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, TARGET_OVER_LINKED);
        }
        return t.withAmount(amount);
    }

    /** Σ links to the payout, minus the link being replaced when it points to the same payout. */
    private BigDecimal payoutLinked(WorkerPayoutEntity payout, BankTransactionLinkEntity replaced) {
        if (payout == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal linked = nz(linkDao.sumAmountByWorkerPayoutId(payout.getId()));
        boolean same = replaced != null && replaced.getWorkerPayout() != null
                && replaced.getWorkerPayout().getId().equals(payout.getId());
        return same ? linked.subtract(replaced.getAmount()) : linked;
    }

    /** Σ links to the document, minus the link being replaced when it points to the same document. */
    private BigDecimal documentLinked(ProcurementDocumentEntity document, BankTransactionLinkEntity replaced) {
        if (document == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal linked = nz(linkDao.sumAmountByProcurementDocumentId(document.getId()));
        boolean same = replaced != null && replaced.getProcurementDocument() != null
                && replaced.getProcurementDocument().getId().equals(document.getId());
        return same ? linked.subtract(replaced.getAmount()) : linked;
    }

    /** Line remainder, capped by the target remainder; a PLANNED payout defaults to its own amount. */
    private static BigDecimal defaultAmount(BigDecimal lineRemainder, WorkerPayoutEntity payout,
                                            BigDecimal payoutLinked, ProcurementDocumentEntity document,
                                            BigDecimal documentLinked) {
        if (payout != null) {
            if (payout.getStatus() == PayoutStatus.PLANNED) {
                return scale(payout.getAmount());
            }
            return scale(lineRemainder.min(payout.getAmount().subtract(payoutLinked)));
        }
        if (document != null && document.getDocumentTotal() != null) {
            return scale(lineRemainder.min(document.getDocumentTotal().subtract(documentLinked)));
        }
        return lineRemainder;
    }

    private BankTransactionLinkEntity persist(BankTransactionEntity txn, Resolved r, DetectionSource source,
                                              BigDecimal confidence, boolean confirmed) {
        BankTransactionLinkEntity link = persistWithoutAudit(txn, r, source, confidence, confirmed);
        audit.write(BankTransactionLinkEntity.class, link.getId(), "CREATE", null, BankLedgerAudit.linkSnapshot(link));
        return link;
    }

    /** Saves the link (so it has an id for the external ref), then runs its effects. */
    private BankTransactionLinkEntity persistWithoutAudit(BankTransactionEntity txn, Resolved r,
                                                          DetectionSource source, BigDecimal confidence,
                                                          boolean confirmed) {
        BankTransactionLinkEntity link = new BankTransactionLinkEntity();
        link.setTransaction(txn);
        link.setKind(r.kind());
        link.setAmount(r.amount());
        link.setWorkerPayout(r.payout());
        link.setProcurementDocument(r.document());
        link.setPaymentSchedule(r.schedule());
        link.setProject(r.project());
        link.setUnplannedCategory(r.category());
        link.setCounterAccount(r.counterAccount());
        link.setSource(source);
        link.setConfidence(source == DetectionSource.MANUAL ? null : confidence);
        link.setNote(r.note());
        if (confirmed) {
            link.setConfirmedAt(OffsetDateTime.now());
            link.setConfirmedBy(BankLedgerAudit.currentUser());
        }
        BankTransactionLinkEntity saved = linkDao.save(link);
        entityManager.flush();
        effects.apply(txn, saved);
        if (saved.getPaymentTransaction() != null) {
            saved = linkDao.save(saved);
        }
        return saved;
    }

    private List<BankTransactionSuggestionEntity> storeSuggestions(BankTransactionEntity txn,
                                                                   List<SuggestionDraft> drafts, int firstRank) {
        List<BankTransactionSuggestionEntity> saved = new ArrayList<>();
        if (drafts == null || txn.getIgnoredReason() != null) {
            return saved;
        }
        int rank = firstRank;
        for (SuggestionDraft draft : drafts) {
            Resolved r;
            try {
                r = resolveSuggestionTarget(txn, draft.target());
            } catch (ForemenApiException e) {
                log.info("Suggestion skipped for bank transaction {}: {}", txn.getId(), e.getMessage());
                continue;
            }
            BankTransactionSuggestionEntity s = new BankTransactionSuggestionEntity();
            s.setTransaction(txn);
            s.setKind(r.kind());
            s.setAmount(r.amount());
            s.setWorkerPayout(r.payout());
            s.setProcurementDocument(r.document());
            s.setPaymentSchedule(r.schedule());
            s.setProject(r.project());
            s.setUnplannedCategory(r.category());
            s.setCounterAccount(r.counterAccount());
            s.setRank((short) rank++);
            s.setRationale(truncate(draft.rationale()));
            s.setStatus(SuggestionStatus.OPEN);
            s.setSource(draft.source());
            s.setConfidence(confidence(draft.confidence()));
            s.setNote(r.note());
            saved.add(suggestionDao.save(s));
        }
        return saved;
    }

    /**
     * Suggestion targets: direction + required targets as for links; the amount defaults like a link.
     * The allocation bounds are not enforced here ({@link #confirmSuggestion} re-validates). A
     * non-positive amount (e.g. the line is already fully allocated) skips the suggestion.
     */
    private Resolved resolveSuggestionTarget(BankTransactionEntity txn, LinkRequest req) {
        Resolved t = resolveTargets(txn, req);
        BigDecimal amount = req.amount() != null
                ? scale(req.amount())
                : defaultAmount(lineRemainder(txn), t.payout(), payoutLinked(t.payout(), null),
                        t.document(), documentLinked(t.document(), null));
        assertAmount(amount);
        return t.withAmount(amount);
    }

    private static LinkRequest toRequest(BankTransactionSuggestionEntity s) {
        return new LinkRequest(s.getKind(), s.getAmount(),
                s.getWorkerPayout() == null ? null : s.getWorkerPayout().getId(),
                s.getProcurementDocument() == null ? null : s.getProcurementDocument().getId(),
                s.getPaymentSchedule() == null ? null : s.getPaymentSchedule().getId(),
                s.getProject() == null ? null : s.getProject().getId(),
                s.getUnplannedCategory() == null ? null : s.getUnplannedCategory().getId(),
                s.getCounterAccount() == null ? null : s.getCounterAccount().getId(),
                s.getNote());
    }

    private void supersedeOpen(Long transactionId, Long exceptId) {
        for (BankTransactionSuggestionEntity s
                : suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(transactionId, SuggestionStatus.OPEN)) {
            if (exceptId != null && exceptId.equals(s.getId())) {
                continue;
            }
            s.setStatus(SuggestionStatus.SUPERSEDED);
            suggestionDao.save(s);
        }
    }

    /** Open suggestions are moot once the line is fully allocated. */
    private void supersedeIfFullyAllocated(BankTransactionEntity txn) {
        if (lineRemainder(txn).signum() == 0) {
            supersedeOpen(txn.getId(), null);
        }
    }

    private void learn(BankTransactionEntity txn, BankTransactionLinkEntity link, boolean remember) {
        if (remember && txn.getOrigin() == LedgerOrigin.BANK_FEED) {
            ruleService.learn(txn, link);
        }
    }

    /** Recomputes the {@code link_status} cache (R13.5) from the persisted links and open suggestions. */
    private LinkStatus refresh(BankTransactionEntity txn) {
        List<LinkStatusDeriver.LinkFact> facts = linkDao.findByTransactionIdOrderByIdAsc(txn.getId()).stream()
                .map(l -> new LinkStatusDeriver.LinkFact(l.getAmount(), l.getSource(), l.getConfirmedAt() != null))
                .toList();
        int open = (int) suggestionDao.countByTransactionIdAndStatus(txn.getId(), SuggestionStatus.OPEN);
        LinkStatus status = LinkStatusDeriver.derive(txn.getIgnoredReason() != null, txn.getAmount(), facts, open);
        txn.setLinkStatus(status);
        transactionDao.save(txn);
        return status;
    }

    private BigDecimal lineRemainder(BankTransactionEntity txn) {
        return lineRemainder(txn, null);
    }

    private BigDecimal lineRemainder(BankTransactionEntity txn, BankTransactionLinkEntity excluded) {
        BigDecimal linked = sum(linkDao.findByTransactionIdOrderByIdAsc(txn.getId()).stream()
                .filter(l -> excluded == null || !excluded.getId().equals(l.getId()))
                .map(BankTransactionLinkEntity::getAmount).toList());
        return scale(txn.getAmount().abs().subtract(linked));
    }

    private void assertLinkable(BankTransactionEntity txn) {
        if (txn.getIgnoredReason() != null) {
            throw new ForemenApiException(HttpStatus.CONFLICT, TRANSACTION_IGNORED);
        }
        if (txn.getStatus() != BankTxnStatus.POSTED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_POSTED);
        }
    }

    private static void assertKindAllowed(BankTransactionEntity txn, LinkKind kind) {
        String direction = txn.getAmount().signum() < 0 ? ClassificationInput.EXPENSE : ClassificationInput.INCOME;
        if (!ClassifierAnswerValidator.allowedKinds(direction).contains(kind.name())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, KIND_DIRECTION);
        }
    }

    private static void assertUnlocked(BankTransactionLinkEntity link) {
        if (LinkEffects.isLocked(link)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, LOCKED);
        }
    }

    private static void assertAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, AMOUNT_INVALID);
        }
    }

    private static boolean staysPaid(BankTransactionLinkEntity link) {
        return link.getKind() == LinkKind.WORKER_PAYOUT && link.getWorkerPayout() != null
                && link.getWorkerPayout().getStatus() == PayoutStatus.PAID;
    }

    private ProjectEntity optionalProject(Long projectId) {
        return projectId == null ? null : projectDao.findById(projectId).orElseThrow(BankLinkService::notFound);
    }

    private BankTransactionEntity findTxn(Long id) {
        return transactionDao.findById(id).orElseThrow(BankLinkService::notFound);
    }

    private static Long required(Long id) {
        if (id == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TARGET_REQUIRED);
        }
        return id;
    }

    static ForemenApiException notFound() {
        return new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }

    private static Map<String, Object> ignoreSnapshot(BankTransactionEntity txn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ignored", txn.getIgnoredReason() != null);
        m.put("ignoredReason", txn.getIgnoredReason());
        return m;
    }

    private static BigDecimal confidence(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal clamped = value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return clamped.setScale(3, RoundingMode.HALF_UP);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= RATIONALE_MAX ? s : s.substring(0, RATIONALE_MAX);
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(nz(v));
        }
        return total;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
