package com.foremen.service.estimate;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * DRAFT-only free-edit gate for an owning {@link EstimateEntity} (FOR-05-03, Requirements 7.1,
 * 7.2, 7.3; design §6.4 {@code assertDraft}).
 *
 * <p>A pure, stateless validator invoked from the four project-scoped services'
 * create/update/delete hooks (lines, room quantities, per-package prices) before any
 * free-edit write, mirroring the {@code EstimateLineRoomQtyValidator}/
 * {@code EffectivePriceResolver} convention of a stateless Spring {@code @Component} that
 * holds no state and performs no I/O beyond reading the already-loaded entity graph passed
 * to it.
 *
 * <p>Once an estimate moves past {@link EstimateStatus#DRAFT} its lines, per-room
 * quantities, and per-package project prices are no longer freely editable; subsequent
 * changes go through the amendment procedure owned by FOR-05-08 (R7.3). This guard only
 * enforces the "when" (lifecycle stage); ABAC continues to enforce the "who" — the two are
 * orthogonal.
 */
@Component
public class DraftGateGuard {

    private static final String LOCKED_MESSAGE = "error.estimate.locked";

    /**
     * Asserts that {@code estimate} is still in {@link EstimateStatus#DRAFT}, allowing the
     * caller's free-edit write to proceed.
     *
     * @param estimate the owning estimate of the line/roomQty/package-price about to be
     *                  created, updated, or deleted
     * @throws ForemenApiException 409 {@code error.estimate.locked} when
     *                              {@code estimate.status != DRAFT} (R7.2, R7.3)
     */
    public void assertDraft(EstimateEntity estimate) {
        if (estimate.getStatus() != EstimateStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, LOCKED_MESSAGE);
        }
    }
}
