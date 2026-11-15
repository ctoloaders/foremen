package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.BankTransactionSuggestionEntity;
import com.foremen.dao.model.LinkStatus;
import com.foremen.dao.model.SuggestionStatus;
import com.foremen.service.bank.detect.ReverseDocumentMatcher.Outcome;
import com.foremen.service.bank.detect.ReverseDocumentMatcher.Result;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.bank.link.SuggestionDraft;

/**
 * FOR-11-03 (R10.3, R10.4) - the flow shared by {@link ReverseProformaMatcher} and
 * {@link ReverseExternalOrderMatcher}, modelled on {@link ReverseDocumentMatcher}: score the unlinked or
 * partially linked ledger lines against ONE target, apply the decision rule, auto-link or suggest the
 * best line, and publish {@link LedgerMatchProposedEvent} when a suggestion was stored.
 */
abstract class ReverseLedgerMatcherSupport {

    private static final Logger log = LoggerFactory.getLogger(ReverseLedgerMatcherSupport.class);

    static final Set<LinkStatus> CANDIDATE_STATUSES =
            EnumSet.of(LinkStatus.UNLINKED, LinkStatus.SUGGESTED, LinkStatus.PARTIALLY_LINKED);

    protected final BankTransactionDao transactionDao;
    protected final BankTransactionLinkDao linkDao;
    protected final BankTransactionSuggestionDao suggestionDao;
    protected final BankLinkService linkService;
    protected final Executor executor;
    protected final TransactionTemplate requiresNew;
    protected final MatchThresholds thresholds;
    private ApplicationEventPublisher eventPublisher;

    @SuppressWarnings("java:S107")
    protected ReverseLedgerMatcherSupport(BankTransactionDao transactionDao, BankTransactionLinkDao linkDao,
                                          BankTransactionSuggestionDao suggestionDao, BankLinkService linkService,
                                          PlatformTransactionManager transactionManager, Executor executor,
                                          MatchThresholds thresholds) {
        this.transactionDao = transactionDao;
        this.linkDao = linkDao;
        this.suggestionDao = suggestionDao;
        this.linkService = linkService;
        this.executor = executor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.thresholds = thresholds;
    }

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Runs {@code job} on the detection pool; failures are logged and never propagate to the publisher. */
    protected void runAsync(String what, Long id, Runnable job) {
        try {
            executor.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException e) {
                    log.warn("Reverse {} matching failed for {}: {}", what, id, e.toString());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Reverse {} matching not scheduled for {}: {}", what, id, e.toString());
        }
    }

    /**
     * Scores {@code lines} (already restricted to the right direction and date window) with
     * {@code scorer}; the best line is auto-linked or gets one appended suggestion.
     *
     * @param alreadySuggested true for an open suggestion that already points at this target
     */
    protected Result matchLines(List<BankTransactionEntity> lines, TxnFacts.Direction direction,
                                Function<TxnFacts, Optional<MatchCandidate>> scorer,
                                Predicate<BankTransactionSuggestionEntity> alreadySuggested) {
        if (lines.isEmpty()) {
            return Result.NONE;
        }
        Map<Long, BigDecimal> linked = linkedPerLine(lines);
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
            TxnFacts facts = new TxnFacts(direction, remainder, line.getBookingDate(), line.getTitle(),
                    line.getCounterpartyName(), line.getCounterpartyIban(), line.getCounterpartyKey());
            scorer.apply(facts).ifPresent(c -> scored.add(new Scored(line, c)));
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
            log.info("Reverse matching auto-linked {} to transaction {}", best.candidate().targetRef().asString(), lineId);
            return new Result(Outcome.AUTO_LINKED, lineId);
        }
        if (decision == Decision.NONE || best.candidate().score().compareTo(thresholds.suggestThreshold()) < 0) {
            return Result.NONE;
        }
        boolean already = suggestionDao.findByTransactionIdAndStatusOrderByRankAsc(lineId, SuggestionStatus.OPEN)
                .stream().anyMatch(alreadySuggested);
        if (!already) {
            List<BankTransactionSuggestionEntity> saved =
                    linkService.appendSuggestions(lineId, List.of(SuggestionDraft.fromCandidate(best.candidate())));
            if (saved.isEmpty()) {
                return Result.NONE;
            }
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new LedgerMatchProposedEvent(lineId));
            }
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
