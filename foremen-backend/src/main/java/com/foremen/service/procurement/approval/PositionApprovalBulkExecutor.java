package com.foremen.service.procurement.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.dao.PositionApprovalRoundDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;

/**
 * FOR-11-06 (Requirements 8.4, 8.5, 8.6): runs the single-record approval service methods once per item,
 * each in its own {@code REQUIRES_NEW} transaction, so a failing item never rolls back the others. A
 * {@link ForemenApiException} becomes a failed item carrying its message key.
 */
@Component
public class PositionApprovalBulkExecutor {

    /** Maximum number of items of one bulk request (400 above). */
    public static final int MAX_ITEMS = 200;
    public static final String ERROR_TOO_MANY = "error.positionApproval.bulk.tooMany";
    private static final String ERROR_INTERNAL = "error.internal";
    private static final Logger LOG = LoggerFactory.getLogger(PositionApprovalBulkExecutor.class);

    /** One accept item: the position whose open round is accepted, plus the client's price seen. */
    public record AcceptItem(Long positionId, Long optionId, java.math.BigDecimal expectedUnitSalePrice,
                             String comment) {
    }

    public record Failure(Long positionId, String errorKey) {
    }

    public record Result(List<Long> succeeded, List<Failure> failed) {
    }

    private final PositionApprovalService approvalService;
    private final PositionApprovalRoundDao roundDao;
    private final TransactionTemplate requiresNew;

    public PositionApprovalBulkExecutor(PositionApprovalService approvalService, PositionApprovalRoundDao roundDao,
                                        PlatformTransactionManager transactionManager) {
        this.approvalService = approvalService;
        this.roundDao = roundDao;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Throws 400 {@code bulk.tooMany} when the list exceeds {@link #MAX_ITEMS}. */
    public static void assertWithinCap(List<?> items) {
        if (items != null && items.size() > MAX_ITEMS) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_TOO_MANY, MAX_ITEMS);
        }
    }

    /** Accepts the open round of each position on behalf of the client. */
    public Result acceptOnBehalf(List<AcceptItem> items, Decider decider) {
        return runAll(items, AcceptItem::positionId, item -> {
            Long roundId = roundDao.findOpenByPositionId(item.positionId(), ApprovalRoundStatus.OPEN)
                    .map(r -> r.getId())
                    .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND,
                            PositionApprovalService.ERROR_ROUND_NOT_FOUND));
            approvalService.accept(roundId, new AcceptCommand(item.optionId(), item.expectedUnitSalePrice(),
                    item.comment()), decider);
            return item.positionId();
        });
    }

    /** Withdraws each open round; the result is keyed by the round's position id. */
    public Result withdraw(List<Long> roundIds) {
        List<Long> succeeded = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        for (Long roundId : roundIds) {
            Long[] positionId = new Long[1];
            try {
                requiresNew.executeWithoutResult(status -> {
                    positionId[0] = roundId == null ? null : roundDao.findById(roundId)
                            .map(r -> r.getPosition().getId()).orElse(null);
                    approvalService.withdraw(roundId);
                });
                succeeded.add(positionId[0]);
            } catch (ForemenApiException e) {
                failed.add(new Failure(positionId[0], e.getMessageCode()));
            } catch (RuntimeException e) {
                LOG.warn("Bulk withdraw of round {} failed", roundId, e);
                failed.add(new Failure(positionId[0], ERROR_INTERNAL));
            }
        }
        return new Result(succeeded, failed);
    }

    private <I> Result runAll(List<I> items, Function<I, Long> positionIdOf, Function<I, Long> action) {
        List<Long> succeeded = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        for (I item : items) {
            Long positionId = positionIdOf.apply(item);
            try {
                requiresNew.execute(status -> action.apply(item));
                succeeded.add(positionId);
            } catch (ForemenApiException e) {
                failed.add(new Failure(positionId, e.getMessageCode()));
            } catch (RuntimeException e) {
                LOG.warn("Bulk item for position {} failed", positionId, e);
                failed.add(new Failure(positionId, ERROR_INTERNAL));
            }
        }
        return new Result(succeeded, failed);
    }
}
