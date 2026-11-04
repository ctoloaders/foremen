package com.foremen.service.bank.link;

import java.math.BigDecimal;

import com.foremen.dao.model.LinkKind;
import com.foremen.service.bank.detect.MatchCandidate;

/**
 * FOR-07-04 (Requirements 13.1, 13.3, 17.1) — the target shape of a link or a suggestion on a ledger
 * line ({@code POST /bank/transactions/{id}/links {kind, amount, targets…, note}}).
 *
 * <p>Only the targets of the {@link #kind()} are used; foreign targets are ignored (the per-kind DB
 * CHECK keeps them empty):
 * <ul>
 *   <li>{@code WORKER_PAYOUT} → {@link #workerPayoutId()} (required);</li>
 *   <li>{@code PROCUREMENT} → {@link #procurementDocumentId()} (required; project = the document's);</li>
 *   <li>{@code CLIENT_PAYMENT} → {@link #paymentScheduleId()} (required; project = the schedule's);</li>
 *   <li>{@code OTHER_EXPENSE} → {@link #unplannedCategoryId()} (required) + optional {@link #projectId()};</li>
 *   <li>{@code OTHER_INCOME} → optional {@link #projectId()};</li>
 *   <li>{@code INTERNAL_TRANSFER} → optional {@link #counterAccountId()}.</li>
 * </ul>
 *
 * @param kind                  the link kind (required)
 * @param amount                the linked portion ({@code > 0}); {@code null} → the default (the line
 *                              remainder, capped by the target remainder; the payout amount for a
 *                              {@code PLANNED} payout)
 * @param workerPayoutId        payout target
 * @param procurementDocumentId document target
 * @param paymentScheduleId     client payment schedule target
 * @param projectId             optional project (OTHER_EXPENSE / OTHER_INCOME)
 * @param unplannedCategoryId   other-expense category
 * @param counterAccountId      the other side of an internal transfer (bank or cash account)
 * @param note                  free text, may be {@code null}
 */
public record LinkRequest(
        LinkKind kind,
        BigDecimal amount,
        Long workerPayoutId,
        Long procurementDocumentId,
        Long paymentScheduleId,
        Long projectId,
        Long unplannedCategoryId,
        Long counterAccountId,
        String note) {

    /** A request with only the kind and the amount set (targets added through the {@code with*} helpers). */
    public static LinkRequest of(LinkKind kind, BigDecimal amount) {
        return new LinkRequest(kind, amount, null, null, null, null, null, null, null);
    }

    public LinkRequest withWorkerPayout(Long id) {
        return new LinkRequest(kind, amount, id, procurementDocumentId, paymentScheduleId, projectId,
                unplannedCategoryId, counterAccountId, note);
    }

    public LinkRequest withProcurementDocument(Long id) {
        return new LinkRequest(kind, amount, workerPayoutId, id, paymentScheduleId, projectId,
                unplannedCategoryId, counterAccountId, note);
    }

    public LinkRequest withPaymentSchedule(Long id) {
        return new LinkRequest(kind, amount, workerPayoutId, procurementDocumentId, id, projectId,
                unplannedCategoryId, counterAccountId, note);
    }

    public LinkRequest withProject(Long id) {
        return new LinkRequest(kind, amount, workerPayoutId, procurementDocumentId, paymentScheduleId, id,
                unplannedCategoryId, counterAccountId, note);
    }

    public LinkRequest withUnplannedCategory(Long id) {
        return new LinkRequest(kind, amount, workerPayoutId, procurementDocumentId, paymentScheduleId,
                projectId, id, counterAccountId, note);
    }

    public LinkRequest withCounterAccount(Long id) {
        return new LinkRequest(kind, amount, workerPayoutId, procurementDocumentId, paymentScheduleId,
                projectId, unplannedCategoryId, id, note);
    }

    public LinkRequest withNote(String text) {
        return new LinkRequest(kind, amount, workerPayoutId, procurementDocumentId, paymentScheduleId,
                projectId, unplannedCategoryId, counterAccountId, text);
    }

    public LinkRequest withAmount(BigDecimal value) {
        return new LinkRequest(kind, value, workerPayoutId, procurementDocumentId, paymentScheduleId,
                projectId, unplannedCategoryId, counterAccountId, note);
    }

    /**
     * The request for a {@link com.foremen.service.bank.detect.RuleMatcher} candidate (detection, 6.8):
     * the target ref is mapped onto the matching target field, the amount is left to the default.
     */
    public static LinkRequest fromCandidate(MatchCandidate candidate) {
        MatchCandidate.TargetRef ref = candidate.targetRef();
        LinkRequest req = of(candidate.kind(), null).withProject(ref.projectId());
        return switch (ref.type()) {
            case OWN_ACCOUNT -> req.withCounterAccount(ref.id());
            case PAYMENT_SCHEDULE -> req.withPaymentSchedule(ref.id());
            case WORKER_PAYOUT -> req.withWorkerPayout(ref.id());
            case PROCUREMENT_DOCUMENT -> req.withProcurementDocument(ref.id());
            case UNPLANNED_CATEGORY -> req.withUnplannedCategory(ref.id());
        };
    }
}
