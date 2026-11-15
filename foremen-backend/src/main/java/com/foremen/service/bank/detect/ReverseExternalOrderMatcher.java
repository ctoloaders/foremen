package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.BankTransactionDao;
import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.BankTransactionSuggestionDao;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.service.bank.detect.MatchInputs.ExternalOrderCandidate;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.procurement.externalorder.ExternalOrderDocumentsChangedEvent;
import com.foremen.service.procurement.externalorder.ExternalOrderSentEvent;

/**
 * FOR-11-03 (R10.3, R10.4) - when an external order is sent for payment, or its linked supplier documents
 * change, score the existing unlinked or partially linked debit lines against it
 * ({@link RuleMatcher#scoreExternalOrder}) and auto-link the best line or suggest it. Lines: {@code POSTED}
 * expense lines booked within {@value #DAYS_BEFORE}..{@value #DAYS_AFTER} days of the day the order was
 * sent. Same threading and failure rules as {@link ReverseProformaMatcher}.
 */
@Component
public class ReverseExternalOrderMatcher extends ReverseLedgerMatcherSupport {

    static final int DAYS_BEFORE = 30;
    static final int DAYS_AFTER = 90;

    private final ExternalOrderDao orderDao;
    private final ProcurementMatchCandidates candidates;

    @Autowired
    @SuppressWarnings("java:S107")
    public ReverseExternalOrderMatcher(ExternalOrderDao orderDao, BankTransactionDao transactionDao,
                                       BankTransactionLinkDao linkDao, BankTransactionSuggestionDao suggestionDao,
                                       ProcurementMatchCandidates candidates, BankLinkService linkService,
                                       PlatformTransactionManager transactionManager,
                                       @Qualifier(BankDetectionExecutorConfig.EXECUTOR) Executor executor,
                                       @Value("${foremen.bank-ledger.match.auto-threshold:0.90}") BigDecimal autoThreshold,
                                       @Value("${foremen.bank-ledger.match.suggest-threshold:0.50}") BigDecimal suggestThreshold,
                                       @Value("${foremen.bank-ledger.match.margin:0.20}") BigDecimal margin,
                                       @Value("${foremen.bank-ledger.match.max-suggestions:3}") int maxSuggestions) {
        this(orderDao, transactionDao, linkDao, suggestionDao, candidates, linkService, transactionManager,
                executor, new MatchThresholds(autoThreshold, suggestThreshold, margin, maxSuggestions));
    }

    @SuppressWarnings("java:S107")
    ReverseExternalOrderMatcher(ExternalOrderDao orderDao, BankTransactionDao transactionDao,
                                BankTransactionLinkDao linkDao, BankTransactionSuggestionDao suggestionDao,
                                ProcurementMatchCandidates candidates, BankLinkService linkService,
                                PlatformTransactionManager transactionManager, Executor executor,
                                MatchThresholds thresholds) {
        super(transactionDao, linkDao, suggestionDao, linkService, transactionManager, executor, thresholds);
        this.orderDao = orderDao;
        this.candidates = candidates;
    }

    /** {@code AFTER_COMMIT} of the send transaction, asynchronous matching (R10.3). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSent(ExternalOrderSentEvent event) {
        schedule(event == null ? null : event.externalOrderId());
    }

    /** The linked supplier documents changed: new invoice numbers may identify a bank line. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentsChanged(ExternalOrderDocumentsChangedEvent event) {
        schedule(event == null ? null : event.externalOrderId());
    }

    private void schedule(Long orderId) {
        if (orderId == null) {
            return;
        }
        runAsync("external order", orderId, () -> matchOrder(orderId));
    }

    /** Runs the matching for one order in its own transaction. */
    public ReverseDocumentMatcher.Result matchOrder(Long orderId) {
        return Objects.requireNonNullElse(requiresNew.execute(s -> doMatch(orderId)),
                ReverseDocumentMatcher.Result.SKIPPED);
    }

    private ReverseDocumentMatcher.Result doMatch(Long orderId) {
        ExternalOrderEntity order = orderDao.findById(orderId).orElse(null);
        if (order == null || order.getSentAt() == null) {
            return ReverseDocumentMatcher.Result.SKIPPED;
        }
        Optional<ExternalOrderCandidate> target = candidates.externalOrderCandidate(orderId);
        if (target.isEmpty()) {
            return ReverseDocumentMatcher.Result.SKIPPED;
        }
        LocalDate anchor = order.getSentAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        List<BankTransactionEntity> lines = transactionDao.findExpenseLinesForDocumentMatching(CANDIDATE_STATUSES,
                anchor.minusDays(DAYS_BEFORE), anchor.plusDays(DAYS_AFTER));
        return matchLines(lines, TxnFacts.Direction.EXPENSE,
                facts -> RuleMatcher.scoreExternalOrder(facts, target.get()),
                s -> s.getExternalOrder() != null && orderId.equals(s.getExternalOrder().getId()));
    }
}
