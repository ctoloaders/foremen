package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.SuggestionDraft;
import com.foremen.service.procurement.ProcurementDocumentParsedEvent;

/**
 * FOR-07-04 (Requirements 28.2, 28.3; design "Reverse document matching") — when the OCR pipeline parses
 * a procurement document that is still {@code UNMATCHED}, score the existing unlinked / partially linked
 * expense lines against it and auto-link the best line or suggest it.
 *
 * <ol>
 *   <li>Lines: {@code POSTED} expense lines, bank and cash, in {@code UNLINKED} / {@code SUGGESTED} /
 *       {@code PARTIALLY_LINKED} (no link that fully allocates them, not ignored), booked within
 *       −{@value #DAYS_BEFORE}..+{@value #DAYS_AFTER} days of the invoice date (else the upload date). A
 *       partially linked line is scored with its unallocated remainder.</li>
 *   <li>Each line is scored against this one document with the PROCUREMENT rows of the scoring table
 *       ({@link RuleMatcher#scoreProcurement}, the line's counterparty rule counting as the supplier
 *       signal).</li>
 *   <li>The same decision rule ({@link RuleMatcher#decision}): {@code AUTO_APPLY} → a {@code RULE} link on
 *       the best line; otherwise {@code SUGGEST} → one suggestion on the best line (appended after its
 *       open suggestions, unless it already suggests this document).</li>
 * </ol>
 *
 * <p>"Skip {@code LINKED_FROM_LEDGER}": the payment choice is a request parameter, not persisted. A
 * document uploaded from a ledger line is linked to that line right after upload, i.e. long before OCR
 * finishes, so it is no longer {@code UNMATCHED} when the parsed event arrives and is skipped by the
 * {@code UNMATCHED} check. The same check skips {@code PAID_CASH} documents (linked in the upload
 * transaction) and re-processed documents that are already linked.
 *
 * <p>The listener runs {@code AFTER_COMMIT} of the OCR worker's transaction and hands the work to the
 * {@link BankDetectionExecutorConfig#EXECUTOR} pool (own transaction); failures are logged and never
 * affect the OCR pipeline.
 */
@Component
public class ReverseDocumentMatcher {

    private static final Logger log = LoggerFactory.getLogger(ReverseDocumentMatcher.class);

    static final int DAYS_BEFORE = 30;
    static final int DAYS_AFTER = 60;
    static final Set<LinkStatus> CANDIDATE_STATUSES =
            EnumSet.of(LinkStatus.UNLINKED, LinkStatus.SUGGESTED, LinkStatus.PARTIALLY_LINKED);

    /** What happened to a document. */
    public enum Outcome {
        /** Unknown document, not {@code UNMATCHED}, or no usable date. */
        SKIPPED,
        /** No line scored at or above the suggest threshold. */
        NONE,
        /** One suggestion stored on the best line. */
        SUGGESTED,
        /** The best line was auto-linked. */
        AUTO_LINKED
    }

    /**
     * @param outcome       the outcome
     * @param transactionId the auto-linked / suggested line, {@code null} otherwise
     */
    public record Result(Outcome outcome, Long transactionId) {
        static final Result SKIPPED = new Result(Outcome.SKIPPED, null);
        static final Result NONE = new Result(Outcome.NONE, null);
    }

    private final ProcurementDocumentDao documentDao;
    private final BankTransactionDao transactionDao;
    private final BankTransactionLinkDao linkDao;
    private final BankTransactionSuggestionDao suggestionDao;
    private final DetectionCandidateProviders providers;
    private final BankLinkService linkService;
    private final Executor executor;
    private final TransactionTemplate requiresNew;
    private final MatchThresholds thresholds;

    @Autowired
    @SuppressWarnings("java:S107")
    public ReverseDocumentMatcher(ProcurementDocumentDao documentDao, BankTransactionDao transactionDao,
                                  BankTransactionLinkDao linkDao, BankTransactionSuggestionDao suggestionDao,
                                  DetectionCandidateProviders providers, BankLinkService linkService,
                                  PlatformTransactionManager transactionManager,
                                  @Qualifier(BankDetectionExecutorConfig.EXECUTOR) Executor executor,
                                  @Value("${foremen.bank-ledger.match.auto-threshold:0.90}") BigDecimal autoThreshold,
                                  @Value("${foremen.bank-ledger.match.suggest-threshold:0.50}") BigDecimal suggestThreshold,
                                  @Value("${foremen.bank-ledger.match.margin:0.20}") BigDecimal margin,
                                  @Value("${foremen.bank-ledger.match.max-suggestions:3}") int maxSuggestions) {
        this(documentDao, transactionDao, linkDao, suggestionDao, providers, linkService, transactionManager,
                executor, new MatchThresholds(autoThreshold, suggestThreshold, margin, maxSuggestions));
    }

    @SuppressWarnings("java:S107")
    ReverseDocumentMatcher(ProcurementDocumentDao documentDao, BankTransactionDao transactionDao,
                           BankTransactionLinkDao linkDao, BankTransactionSuggestionDao suggestionDao,
                           DetectionCandidateProviders providers, BankLinkService linkService,
                           PlatformTransactionManager transactionManager, Executor executor,
                           MatchThresholds thresholds) {
        this.documentDao = documentDao;
        this.transactionDao = transactionDao;
        this.linkDao = linkDao;
        this.suggestionDao = suggestionDao;
        this.providers = providers;
        this.linkService = linkService;
        this.executor = executor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.thresholds = thresholds;
    }

    /** {@code AFTER_COMMIT} of the OCR transaction → asynchronous matching (R28.2). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParsed(ProcurementDocumentParsedEvent event) {
        if (event == null || event.documentId() == null) {
            return;
        }
        Long documentId = event.documentId();
        try {
            executor.execute(() -> {
                try {
                    matchDocument(documentId);
                } catch (RuntimeException e) {
                    log.warn("Reverse document matching failed for document {}: {}", documentId, e.toString());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Reverse document matching not scheduled for document {}: {}", documentId, e.toString());
        }
    }

    /** Runs the matching for one document in its own transaction. */
    public Result matchDocument(Long documentId) {
        return Objects.requireNonNullElse(requiresNew.execute(s -> doMatch(documentId)), Result.SKIPPED);
    }

    private Result doMatch(Long documentId) {
        ProcurementDocumentEntity doc = documentDao.findById(documentId).orElse(null);
        if (doc == null) {
            return Result.SKIPPED;
        }
        DocumentCandidate candidate = DetectionCandidateProviders.toDocumentCandidate(doc,
                linkDao.sumAmountByProcurementDocumentId(documentId));
        if (candidate.paymentStatus() != DocumentPaymentStatus.UNMATCHED) {
            return Result.SKIPPED;
        }
        LocalDate anchor = doc.getInvoiceDate() != null ? doc.getInvoiceDate()
                : doc.getUploadedAt() == null ? null : doc.getUploadedAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        if (anchor == null) {
            return Result.SKIPPED;
        }
        List<BankTransactionEntity> lines = transactionDao.findExpenseLinesForDocumentMatching(CANDIDATE_STATUSES,
                anchor.minusDays(DAYS_BEFORE), anchor.plusDays(DAYS_AFTER));
        if (lines.isEmpty()) {
            return Result.NONE;
        }
        Map<Long, BigDecimal> linked = linkedPerLine(lines);
        Map<String, List<CounterpartyRule>> ruleCache = new HashMap<>();

        record Scored(BankTransactionEntity line, MatchCandidate candidate) {
        }
        List<Scored> scored = new ArrayList<>();
        for (BankTransactionEntity line : lines) {
            if (line.getIgnoredReason() != null || line.getBookingDate() == null) {
                continue;
            }
            BigDecimal remainder = line.getAmount().abs().subtract(linked.getOrDefault(line.getId(), BigDecimal.ZERO))
                    .setScale(2, RoundingMode.HALF_UP);
            if (remainder.signum() <= 0) {
                continue;
            }
            TxnFacts facts = new TxnFacts(TxnFacts.Direction.EXPENSE, remainder, line.getBookingDate(),
                    line.getTitle(), line.getCounterpartyName(), line.getCounterpartyIban(), line.getCounterpartyKey());
            List<CounterpartyRule> rules = line.getCounterpartyKey() == null ? List.of()
                    : ruleCache.computeIfAbsent(line.getCounterpartyKey(), providers::counterpartyRules);
            RuleMatcher.scoreProcurement(facts, candidate, rules).ifPresent(c -> scored.add(new Scored(line, c)));
        }
        if (scored.isEmpty()) {
            return Result.NONE;
        }
        scored.sort(Comparator.comparing((Scored x) -> x.candidate().score(), Comparator.reverseOrder())
                .thenComparing(x -> x.line().getId()));
        Decision decision = RuleMatcher.decision(scored.stream().map(x -> x.candidate().score()).toList(), thresholds);
        Scored best = scored.get(0);
        Long lineId = best.line().getId();
        if (decision == Decision.AUTO_APPLY && linkService.applyAuto(lineId, best.candidate()).isPresent()) {
            log.info("Reverse matching auto-linked document {} to transaction {}", documentId, lineId);
            return new Result(Outcome.AUTO_LINKED, lineId);
        }
        if (decision == Decision.NONE
                || best.candidate().score().compareTo(thresholds.suggestThreshold()) < 0) {
            return Result.NONE;
        }
        boolean alreadySuggested = suggestionDao
                .findByTransactionIdAndStatusOrderByRankAsc(lineId, SuggestionStatus.OPEN).stream()
                .anyMatch(s -> s.getProcurementDocument() != null
                        && documentId.equals(s.getProcurementDocument().getId()));
        if (!alreadySuggested
                && linkService.appendSuggestions(lineId, List.of(SuggestionDraft.fromCandidate(best.candidate())))
                        .isEmpty()) {
            return Result.NONE;
        }
        return new Result(Outcome.SUGGESTED, lineId);
    }

    private Map<Long, BigDecimal> linkedPerLine(List<BankTransactionEntity> lines) {
        Map<Long, BigDecimal> out = new HashMap<>();
        List<Long> partial = lines.stream()
                .filter(l -> l.getLinkStatus() == LinkStatus.PARTIALLY_LINKED)
                .map(BankTransactionEntity::getId)
                .toList();
        if (partial.isEmpty()) {
            return out;
        }
        for (BankTransactionLinkEntity l : linkDao.findByTransactionIdIn(partial)) {
            out.merge(l.getTransaction().getId(), l.getAmount(), BigDecimal::add);
        }
        return out;
    }
}
