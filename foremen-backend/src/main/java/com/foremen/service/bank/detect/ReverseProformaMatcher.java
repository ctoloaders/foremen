package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.service.bank.detect.MatchInputs.ProformaCandidate;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.procurement.proforma.ProformaIssuedEvent;

/**
 * FOR-11-03 (R10.3, R10.4) - when a proforma is issued, score the existing unlinked or partially linked
 * credit lines against it ({@link RuleMatcher#scoreProforma}) and auto-link the best line or suggest it.
 * Lines: {@code POSTED} income lines booked within
 * {@value #DAYS_BEFORE}..{@value #DAYS_AFTER} days of the issue date. The listener runs
 * {@code AFTER_COMMIT} of the issue transaction and hands the work to the
 * {@link BankDetectionExecutorConfig#EXECUTOR} pool (own transaction); failures are logged only.
 */
@Component
public class ReverseProformaMatcher extends ReverseLedgerMatcherSupport {

    static final int DAYS_BEFORE = 30;
    static final int DAYS_AFTER = 90;

    private final ClientProformaDao proformaDao;
    private final ProcurementMatchCandidates candidates;

    @Autowired
    @SuppressWarnings("java:S107")
    public ReverseProformaMatcher(ClientProformaDao proformaDao, BankTransactionDao transactionDao,
                                  BankTransactionLinkDao linkDao, BankTransactionSuggestionDao suggestionDao,
                                  ProcurementMatchCandidates candidates, BankLinkService linkService,
                                  PlatformTransactionManager transactionManager,
                                  @Qualifier(BankDetectionExecutorConfig.EXECUTOR) Executor executor,
                                  @Value("${foremen.bank-ledger.match.auto-threshold:0.90}") BigDecimal autoThreshold,
                                  @Value("${foremen.bank-ledger.match.suggest-threshold:0.50}") BigDecimal suggestThreshold,
                                  @Value("${foremen.bank-ledger.match.margin:0.20}") BigDecimal margin,
                                  @Value("${foremen.bank-ledger.match.max-suggestions:3}") int maxSuggestions) {
        this(proformaDao, transactionDao, linkDao, suggestionDao, candidates, linkService, transactionManager,
                executor, new MatchThresholds(autoThreshold, suggestThreshold, margin, maxSuggestions));
    }

    @SuppressWarnings("java:S107")
    ReverseProformaMatcher(ClientProformaDao proformaDao, BankTransactionDao transactionDao,
                           BankTransactionLinkDao linkDao, BankTransactionSuggestionDao suggestionDao,
                           ProcurementMatchCandidates candidates, BankLinkService linkService,
                           PlatformTransactionManager transactionManager, Executor executor,
                           MatchThresholds thresholds) {
        super(transactionDao, linkDao, suggestionDao, linkService, transactionManager, executor, thresholds);
        this.proformaDao = proformaDao;
        this.candidates = candidates;
    }

    /** {@code AFTER_COMMIT} of the issue transaction, asynchronous matching (R10.3). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIssued(ProformaIssuedEvent event) {
        if (event == null || event.proformaId() == null) {
            return;
        }
        Long id = event.proformaId();
        runAsync("proforma", id, () -> matchProforma(id));
    }

    /** Runs the matching for one proforma in its own transaction. */
    public ReverseDocumentMatcher.Result matchProforma(Long proformaId) {
        return Objects.requireNonNullElse(requiresNew.execute(s -> doMatch(proformaId)),
                ReverseDocumentMatcher.Result.SKIPPED);
    }

    private ReverseDocumentMatcher.Result doMatch(Long proformaId) {
        ClientProformaEntity proforma = proformaDao.findById(proformaId).orElse(null);
        if (proforma == null || proforma.getIssueDate() == null) {
            return ReverseDocumentMatcher.Result.SKIPPED;
        }
        List<ProformaCandidate> targets = candidates.proformaCandidates(proformaId);
        if (targets.isEmpty()) {
            return ReverseDocumentMatcher.Result.SKIPPED;
        }
        LocalDate anchor = proforma.getIssueDate();
        List<BankTransactionEntity> lines = transactionDao.findIncomeLinesForProformaMatching(CANDIDATE_STATUSES,
                anchor.minusDays(DAYS_BEFORE), anchor.plusDays(DAYS_AFTER));
        return matchLines(lines, TxnFacts.Direction.INCOME,
                facts -> targets.stream()
                        .map(t -> RuleMatcher.scoreProforma(facts, t))
                        .flatMap(java.util.Optional::stream)
                        .max(java.util.Comparator.comparing(MatchCandidate::score)),
                s -> s.getClientProforma() != null && proformaId.equals(s.getClientProforma().getId()));
    }
}
