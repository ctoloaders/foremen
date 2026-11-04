package com.foremen.service.bank.ledger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.foremen.dao.model.LinkStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.detect.DetectionService;
import com.foremen.service.bank.detect.DetectionService.DetectionSummary;
import com.foremen.service.bank.link.BankLinkService;

/**
 * FOR-07-04 (Requirements 17.6, 20.7) — the ledger bulk actions. Deliberately <b>not</b>
 * {@code @Transactional}: every line is confirmed in its own {@link BankLinkService} transaction, so one
 * failing line (e.g. a target linked meanwhile) does not roll back the others; failures are reported per
 * line.
 */
@Service
public class BankLedgerBulkService {

    private static final Logger log = LoggerFactory.getLogger(BankLedgerBulkService.class);

    /** 400 — more lines than {@link #MAX_SELECTION} in one bulk request. */
    public static final String TOO_MANY = "error.bank.bulk.tooMany";
    /** 400 — "re-run detection" needs ids or a filter. */
    public static final String SELECTION_REQUIRED = "error.bank.detect.selectionRequired";

    /** Max lines per bulk request (ids or filter-resolved). */
    public static final int MAX_SELECTION = 500;

    private final BankLinkService linkService;
    private final BankLedgerQueryService queryService;
    private final DetectionService detectionService;

    public BankLedgerBulkService(BankLinkService linkService, BankLedgerQueryService queryService,
                                 DetectionService detectionService) {
        this.linkService = linkService;
        this.queryService = queryService;
        this.detectionService = detectionService;
    }

    /** One line that could not be confirmed, with its error code. */
    public record BulkFailure(Long transactionId, String code) {
    }

    /**
     * @param confirmed lines confirmed (auto links confirmed or the top suggestion applied)
     * @param skipped   lines with nothing to confirm
     * @param failed    lines rejected by the link service
     */
    public record BulkConfirmResult(int confirmed, int skipped, List<BulkFailure> failed) {
    }

    /**
     * "Confirm all auto-links / top suggestions" (R20.7): an {@code AUTO_LINKED} line → its auto links are
     * confirmed ({@link BankLinkService#confirmAuto}); a {@code SUGGESTED} line → its best-ranked open
     * suggestion is confirmed; every other line is skipped.
     */
    public BulkConfirmResult confirm(List<Long> transactionIds, boolean remember) {
        List<Long> ids = distinct(transactionIds);
        int confirmed = 0;
        int skipped = 0;
        List<BulkFailure> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                LinkStatus status = queryService.linkStatus(id);
                if (status == LinkStatus.AUTO_LINKED) {
                    linkService.confirmAuto(id, remember);
                    confirmed++;
                } else if (status == LinkStatus.SUGGESTED) {
                    Optional<Long> top = queryService.topOpenSuggestionId(id);
                    if (top.isPresent()) {
                        linkService.confirmSuggestion(top.get(), remember);
                        confirmed++;
                    } else {
                        skipped++;
                    }
                } else {
                    skipped++;
                }
            } catch (ForemenApiException e) {
                log.info("Bulk confirm skipped bank transaction {}: {}", id, e.getMessageCode());
                failed.add(new BulkFailure(id, e.getMessageCode()));
            }
        }
        return new BulkConfirmResult(confirmed, skipped, List.copyOf(failed));
    }

    /**
     * "Re-run detection" (R17.6) on explicit ids or, when none are given, on the {@code UNLINKED} /
     * {@code SUGGESTED} lines of {@code filter} (at most {@link #MAX_SELECTION}).
     */
    public DetectionSummary detect(List<Long> transactionIds, LedgerFilter filter, boolean includeAi) {
        List<Long> ids = distinct(transactionIds);
        if (ids.isEmpty()) {
            if (filter == null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, SELECTION_REQUIRED);
            }
            ids = queryService.idsForDetection(filter, MAX_SELECTION);
        }
        if (ids.isEmpty()) {
            return DetectionSummary.empty();
        }
        return detectionService.rerunDetection(ids, includeAi);
    }

    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        List<Long> out = new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
        if (out.size() > MAX_SELECTION) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TOO_MANY);
        }
        return out;
    }
}
