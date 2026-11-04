package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTxnStatus;
import com.foremen.dao.model.DetectionSource;
import com.foremen.dao.model.LedgerOrigin;
import com.foremen.dao.model.LinkKind;
import com.foremen.dao.model.LinkStatus;
import com.foremen.service.bank.detect.DetectionCandidateProviders.AiCatalog;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.RuleMatcher.MatchResult;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.LinkOutcome;
import com.foremen.service.bank.link.LinkRequest;
import com.foremen.service.bank.link.SuggestionDraft;

/**
 * FOR-07-04 (Requirements 14, 15.2, 15.5, 15.6, 17.6; design §Detection) — auto-detection of ledger lines.
 *
 * <p><b>Scope</b> (R14.1, R27.7): only {@code POSTED} {@code BANK_FEED} lines that are not ignored and carry
 * no links. Cash lines ({@code MANUAL} / {@code SYSTEM}) are never auto-detected.
 *
 * <p><b>Rule step</b> (synchronous): per batch the {@link DetectionCandidateProviders} load the
 * {@link CandidatePool} once; per line the pool is narrowed ({@link CandidatePool#inputsFor}) and the pure
 * {@link RuleMatcher} decides:
 * <ul>
 *   <li>{@code AUTO_APPLY} → {@link BankLinkService#applyAuto} (a {@code RULE} link + its effects). When
 *       the candidate no longer validates (e.g. the target got linked meanwhile) the line falls back to the
 *       remaining candidates as suggestions;</li>
 *   <li>{@code SUGGEST} → {@link BankLinkService#replaceSuggestions} (top {@code max-suggestions});</li>
 *   <li>{@code NONE} → nothing.</li>
 * </ul>
 * Each line runs in its own {@code REQUIRES_NEW} transaction so a failing line never affects the others
 * nor the caller; callers pass ids of lines that are already committed (the sync orchestrator calls
 * {@link #detect} after the ingest transaction).
 *
 * <p><b>AI step</b> (asynchronous, R15.5–R15.7): lines left without an auto-link and never classified
 * ({@code ai_classified_at IS NULL}) are queued, at most {@code foremen.bank-ledger.ai.max-per-run} per
 * call, and classified on the {@link BankDetectionExecutorConfig#EXECUTOR} pool after the caller's
 * transaction commits. {@code ai_classified_at} is set before the call (once per line, even when the call
 * fails); the answer is stored as one {@code AI} suggestion ranked after the rule ones and never
 * auto-applied. Nothing is queued while the classifier is disabled.
 *
 * <p><b>Thresholds</b> (R14.7): {@code foremen.bank-ledger.match.auto-threshold} (0.90),
 * {@code suggest-threshold} (0.50), {@code margin} (0.20), {@code max-suggestions} (3).
 */
@Service
public class DetectionService {

    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    /** {@code To_Review} statuses (glossary) — plus {@code AUTO_LINKED} not yet reviewed. */
    static final Set<LinkStatus> TO_REVIEW = EnumSet.of(LinkStatus.UNLINKED, LinkStatus.SUGGESTED,
            LinkStatus.PARTIALLY_LINKED);

    private final BankTransactionDao transactionDao;
    private final BankTransactionLinkDao linkDao;
    private final DetectionCandidateProviders providers;
    private final BankLinkService linkService;
    private final BankTransactionClassifier classifier;
    private final int aiMaxPerRun;
    private final Executor executor;
    private final TransactionTemplate requiresNew;
    private final RuleMatcher matcher;

    /**
     * The result of a detection call.
     *
     * @param processed  eligible lines processed (rules ran)
     * @param autoLinked lines auto-linked by a rule
     * @param suggested  lines with stored rule suggestions
     * @param aiQueued   lines queued for the asynchronous AI step
     * @param toReview   eligible lines left in {@code To_Review} after the rule step (UNLINKED, SUGGESTED,
     *                   PARTIALLY_LINKED, or AUTO_LINKED not yet reviewed)
     * @param skipped    ids that were not detected (unknown, not BANK_FEED / POSTED, ignored, linked)
     */
    public record DetectionSummary(int processed, int autoLinked, int suggested, int aiQueued, int toReview,
                                   int skipped) {

        public static DetectionSummary empty() {
            return new DetectionSummary(0, 0, 0, 0, 0, 0);
        }
    }

    /** One line queued for the AI step. */
    record AiWork(Long transactionId, List<MatchCandidate> ranked, List<ClassificationInput.Candidate> candidates,
                  boolean force) {
    }

    /** The outcome of the rule step on one line. */
    private record LineResult(boolean processed, boolean autoLinked, boolean suggested, boolean toReview,
                              AiWork ai) {
        static final LineResult SKIPPED = new LineResult(false, false, false, false, null);
    }

    /** Per-batch mutable state: targets consumed by auto-links and the rule cache. */
    private static final class BatchState {
        final Set<String> consumed = new HashSet<>();
        final Map<String, List<CounterpartyRule>> rules = new HashMap<>();

        List<CounterpartyRule> rules(String key, Function<String, List<CounterpartyRule>> loader) {
            if (key == null || key.isBlank()) {
                return List.of();
            }
            return rules.computeIfAbsent(key, loader);
        }
    }

    @Autowired
    @SuppressWarnings("java:S107")
    public DetectionService(BankTransactionDao transactionDao, BankTransactionLinkDao linkDao,
                            DetectionCandidateProviders providers, BankLinkService linkService,
                            BankTransactionClassifier classifier, BankAiProperties aiProperties,
                            PlatformTransactionManager transactionManager,
                            @Qualifier(BankDetectionExecutorConfig.EXECUTOR) Executor executor,
                            @Value("${foremen.bank-ledger.match.auto-threshold:0.90}") BigDecimal autoThreshold,
                            @Value("${foremen.bank-ledger.match.suggest-threshold:0.50}") BigDecimal suggestThreshold,
                            @Value("${foremen.bank-ledger.match.margin:0.20}") BigDecimal margin,
                            @Value("${foremen.bank-ledger.match.max-suggestions:3}") int maxSuggestions) {
        this(transactionDao, linkDao, providers, linkService, classifier, aiProperties, transactionManager, executor,
                new MatchThresholds(autoThreshold, suggestThreshold, margin, maxSuggestions));
    }

    @SuppressWarnings("java:S107")
    DetectionService(BankTransactionDao transactionDao, BankTransactionLinkDao linkDao,
                     DetectionCandidateProviders providers, BankLinkService linkService,
                     BankTransactionClassifier classifier, BankAiProperties aiProperties,
                     PlatformTransactionManager transactionManager, Executor executor,
                     MatchThresholds thresholds) {
        this.transactionDao = transactionDao;
        this.linkDao = linkDao;
        this.providers = providers;
        this.linkService = linkService;
        this.classifier = classifier;
        this.aiMaxPerRun = aiProperties == null || aiProperties.maxPerRun() == null
                ? BankAiProperties.DEFAULT_MAX_PER_RUN : aiProperties.maxPerRun();
        this.executor = executor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.matcher = new RuleMatcher(thresholds);
    }

    public MatchThresholds thresholds() {
        return matcher.thresholds();
    }

    // ============================================================================================
    // API
    // ============================================================================================

    /**
     * Detects newly posted lines of a sync run (task 6.4): rules synchronously, AI asynchronously after the
     * caller's transaction commits (immediately when called outside a transaction).
     *
     * @param newPostedIds ids of lines that became {@code POSTED} in this run (non-eligible ids are skipped)
     * @param runId        the sync run (logging only), may be {@code null}
     */
    public DetectionSummary detect(List<Long> newPostedIds, Long runId) {
        DetectionSummary summary = process(newPostedIds, false, false);
        log.info("Bank detection run={} processed={} auto={} suggested={} aiQueued={} toReview={} skipped={}",
                runId, summary.processed(), summary.autoLinked(), summary.suggested(), summary.aiQueued(),
                summary.toReview(), summary.skipped());
        return summary;
    }

    /** "Re-run detection" on one line (R17.6) — see {@link #rerunDetection(Collection, boolean)}. */
    public DetectionSummary rerunDetection(Long transactionId, boolean rerunAi) {
        return rerunDetection(List.of(transactionId), rerunAi);
    }

    /**
     * "Re-run detection" (R17.6) on lines without links: their {@code OPEN} suggestions are superseded and
     * the rules run again; the AI step runs when {@code rerunAi} is requested or the line was never
     * classified. Lines with links (AUTO_LINKED / PARTIALLY_LINKED / LINKED), ignored, cash or PENDING lines
     * are skipped.
     */
    public DetectionSummary rerunDetection(Collection<Long> transactionIds, boolean rerunAi) {
        return process(transactionIds, true, rerunAi);
    }

    // ============================================================================================
    // Rule step
    // ============================================================================================

    private DetectionSummary process(Collection<Long> ids, boolean rerun, boolean forceAi) {
        List<Long> distinct = ids == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
        if (distinct.isEmpty()) {
            return DetectionSummary.empty();
        }
        CandidatePool pool = Objects.requireNonNullElse(requiresNew.execute(s -> {
            List<TxnFacts> facts = new ArrayList<>();
            for (BankTransactionEntity t : transactionDao.findAllById(distinct)) {
                if (eligible(t)) {
                    facts.add(facts(t));
                }
            }
            return providers.load(facts);
        }), CandidatePool.empty());

        BatchState state = new BatchState();
        int processed = 0;
        int auto = 0;
        int suggested = 0;
        int toReview = 0;
        int skipped = 0;
        List<AiWork> aiQueue = new ArrayList<>();
        for (Long id : distinct) {
            LineResult r;
            try {
                r = requiresNew.execute(s -> detectLine(id, pool, state, rerun, forceAi));
            } catch (RuntimeException e) {
                log.warn("Bank detection failed for transaction {}: {}", id, e.toString());
                // The line stays as it was (UNLINKED for a new line).
                r = new LineResult(true, false, false, true, null);
            }
            if (r == null || !r.processed()) {
                skipped++;
                continue;
            }
            processed++;
            auto += r.autoLinked() ? 1 : 0;
            suggested += r.suggested() ? 1 : 0;
            toReview += r.toReview() ? 1 : 0;
            if (r.ai() != null && aiQueue.size() < aiMaxPerRun) {
                aiQueue.add(r.ai());
            }
        }
        scheduleAi(aiQueue);
        return new DetectionSummary(processed, auto, suggested, aiQueue.size(), toReview, skipped);
    }

    private LineResult detectLine(Long id, CandidatePool pool, BatchState state, boolean rerun, boolean forceAi) {
        BankTransactionEntity txn = transactionDao.findById(id).orElse(null);
        if (!eligible(txn) || !linkDao.findByTransactionIdOrderByIdAsc(id).isEmpty()) {
            return LineResult.SKIPPED;
        }
        if (rerun) {
            linkService.clearOpenSuggestions(id);
        }
        TxnFacts facts = facts(txn);
        List<CounterpartyRule> rules = state.rules(facts.counterpartyKey(), providers::counterpartyRules);
        MatchResult result = matcher.match(facts, pool.inputsFor(facts, rules, state.consumed));

        LinkStatus status = rerun ? LinkStatus.UNLINKED : txn.getLinkStatus();
        boolean autoLinked = false;
        boolean suggested = false;
        Optional<MatchCandidate> autoCandidate = result.auto();
        if (autoCandidate.isPresent()) {
            MatchCandidate c = autoCandidate.get();
            Optional<LinkOutcome> outcome = linkService.applyAuto(id, c);
            if (outcome.isPresent()) {
                autoLinked = true;
                status = outcome.get().linkStatus();
                state.consumed.add(CandidatePool.consumedKey(c.targetRef()));
            } else {
                List<SuggestionDraft> drafts = result.ranked().stream()
                        .filter(x -> !x.equals(c))
                        .filter(x -> x.score().compareTo(matcher.thresholds().suggestThreshold()) >= 0)
                        .limit(matcher.thresholds().maxSuggestions())
                        .map(SuggestionDraft::fromCandidate)
                        .toList();
                suggested = storeSuggestions(id, drafts);
            }
        } else if (result.decision() == Decision.SUGGEST) {
            suggested = storeSuggestions(id, result.selected().stream().map(SuggestionDraft::fromCandidate).toList());
        }
        if (suggested) {
            status = LinkStatus.SUGGESTED;
        }
        boolean toReview = TO_REVIEW.contains(status)
                || (status == LinkStatus.AUTO_LINKED && txn.getReviewedAt() == null);

        AiWork ai = null;
        boolean fullyAllocated = status == LinkStatus.AUTO_LINKED || status == LinkStatus.LINKED;
        if (!autoLinked && !fullyAllocated && classifier.isEnabled()
                && (forceAi || txn.getAiClassifiedAt() == null)) {
            ai = new AiWork(id, result.ranked(), pool.aiCandidates(result.ranked()), forceAi);
        }
        return new LineResult(true, autoLinked, suggested, toReview, ai);
    }

    private boolean storeSuggestions(Long id, List<SuggestionDraft> drafts) {
        if (drafts.isEmpty()) {
            return false;
        }
        return !linkService.replaceSuggestions(id, drafts).isEmpty();
    }

    /** R14.1 / R27.7 — POSTED BANK_FEED lines that are not ignored. */
    static boolean eligible(BankTransactionEntity t) {
        return t != null
                && t.getOrigin() == LedgerOrigin.BANK_FEED
                && t.getStatus() == BankTxnStatus.POSTED
                && t.getIgnoredReason() == null
                && t.getBookingDate() != null
                && t.getAmount() != null && t.getAmount().signum() != 0;
    }

    static TxnFacts facts(BankTransactionEntity t) {
        return new TxnFacts(direction(t), t.getAmount(), t.getBookingDate(), t.getTitle(), t.getCounterpartyName(),
                t.getCounterpartyIban(), t.getCounterpartyKey());
    }

    static TxnFacts.Direction direction(BankTransactionEntity t) {
        return t.getAmount().signum() < 0 ? TxnFacts.Direction.EXPENSE : TxnFacts.Direction.INCOME;
    }

    // ============================================================================================
    // AI step
    // ============================================================================================

    private void scheduleAi(List<AiWork> work) {
        if (work.isEmpty()) {
            return;
        }
        List<AiWork> copy = List.copyOf(work);
        Runnable job = () -> submit(copy);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
        }
    }

    private void submit(List<AiWork> work) {
        try {
            executor.execute(() -> runAi(work));
        } catch (RuntimeException e) {
            log.warn("Bank AI classification of {} transaction(s) not scheduled: {}", work.size(), e.toString());
        }
    }

    /** Classifies the queued lines one by one; every failure leaves the line as it is (R15.6). */
    void runAi(List<AiWork> work) {
        AiCatalog catalog;
        try {
            catalog = Objects.requireNonNullElse(requiresNew.execute(s -> providers.aiCatalog()), AiCatalog.empty());
        } catch (RuntimeException e) {
            log.warn("Bank AI classification skipped, catalog unavailable: {}", e.toString());
            return;
        }
        for (AiWork w : work) {
            try {
                classifyOne(w, catalog);
            } catch (RuntimeException e) {
                log.warn("Bank AI classification failed for transaction {}: {}", w.transactionId(), e.toString());
            }
        }
    }

    private void classifyOne(AiWork w, AiCatalog catalog) {
        // 1. claim the line (ai_classified_at, once per line) and build the input — own transaction.
        ClassificationInput input = requiresNew.execute(s -> claim(w, catalog));
        if (input == null) {
            return;
        }
        // 2. the network call, outside any transaction.
        Optional<ClassifierAnswer> answer = classifier.classify(input);
        if (answer.isEmpty()) {
            return;
        }
        ClassifierAnswer validated = ClassifierAnswerValidator.validate(answer.get(), input);
        Optional<SuggestionDraft> draft = toDraft(validated, w.ranked(), catalog);
        if (draft.isEmpty()) {
            return;
        }
        // 3. store the AI suggestion after the rule ones — own transaction, re-checking the line.
        requiresNew.executeWithoutResult(s -> {
            BankTransactionEntity txn = transactionDao.findById(w.transactionId()).orElse(null);
            if (eligible(txn) && linkDao.findByTransactionIdOrderByIdAsc(w.transactionId()).isEmpty()) {
                linkService.appendSuggestions(w.transactionId(), List.of(draft.get()));
            }
        });
    }

    private ClassificationInput claim(AiWork w, AiCatalog catalog) {
        BankTransactionEntity txn = transactionDao.findById(w.transactionId()).orElse(null);
        if (!eligible(txn) || !linkDao.findByTransactionIdOrderByIdAsc(w.transactionId()).isEmpty()) {
            return null;
        }
        if (txn.getAiClassifiedAt() != null && !w.force()) {
            return null;
        }
        txn.setAiClassifiedAt(OffsetDateTime.now());
        transactionDao.save(txn);
        String bankName = txn.getAccount() != null && txn.getAccount().getConnection() != null
                ? txn.getAccount().getConnection().getBankName() : null;
        return new ClassificationInput(direction(txn).name(), txn.getAmount().abs(), txn.getCurrency(),
                txn.getBookingDate(), txn.getTitle(), txn.getCounterpartyName(),
                ClassificationInput.maskIbanLast4(txn.getCounterpartyIban()), bankName,
                w.candidates(), catalog.categories(), catalog.projects());
    }

    /**
     * Maps a validated answer onto a suggestion target (R15.5): a supplied candidate of the same kind, or
     * the kind's own target (OTHER_EXPENSE → category [+ project], OTHER_INCOME → [project],
     * INTERNAL_TRANSFER → none). Kinds that need a target the answer does not carry yield nothing.
     */
    static Optional<SuggestionDraft> toDraft(ClassifierAnswer answer, List<MatchCandidate> ranked, AiCatalog catalog) {
        if (answer == null || !answer.usable()) {
            return Optional.empty();
        }
        LinkKind kind;
        try {
            kind = LinkKind.valueOf(answer.kind().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        LinkRequest request = null;
        if (answer.candidateRef() != null && ranked != null) {
            request = ranked.stream()
                    .filter(c -> c.kind() == kind && c.targetRef().asString().equals(answer.candidateRef()))
                    .findFirst()
                    .map(LinkRequest::fromCandidate)
                    .orElse(null);
        }
        if (request == null) {
            request = switch (kind) {
                case OTHER_EXPENSE -> {
                    Long categoryId = answer.categoryCode() == null ? null
                            : catalog.categoryIds().get(answer.categoryCode());
                    yield categoryId == null ? null
                            : LinkRequest.of(kind, null).withUnplannedCategory(categoryId).withProject(answer.projectId());
                }
                case OTHER_INCOME -> LinkRequest.of(kind, null).withProject(answer.projectId());
                case INTERNAL_TRANSFER -> LinkRequest.of(kind, null);
                default -> null;
            };
        }
        if (request == null) {
            return Optional.empty();
        }
        double c = Math.max(0.0, Math.min(1.0, answer.confidence()));
        BigDecimal confidence = BigDecimal.valueOf(c).setScale(2, RoundingMode.HALF_UP);
        return Optional.of(new SuggestionDraft(request, DetectionSource.AI, confidence, answer.rationale()));
    }
}
