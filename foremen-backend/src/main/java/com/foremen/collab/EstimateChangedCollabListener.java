package com.foremen.collab;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.service.estimate.recalc.EstimateChangedEvent;

/**
 * FOR-05-05 additional task 6 × FOR-05-13 — when a room save re-derived the estimate on the server,
 * tell every open Estimate tab to refetch (a SAVED broadcast that prunes nothing from the shared
 * draft). Estimate-tab saves already broadcast from {@code EstimateMatrixController}, so only the
 * {@code ROOMS_CHANGED} trigger is relayed here. Best effort and after commit only.
 */
@Component
public class EstimateChangedCollabListener {

    private static final Logger log = LoggerFactory.getLogger(EstimateChangedCollabListener.class);

    private final EstimateCollabService collabService;

    public EstimateChangedCollabListener(EstimateCollabService collabService) {
        this.collabService = collabService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEstimateChanged(EstimateChangedEvent event) {
        if (event.trigger() != EstimateChangedEvent.Trigger.ROOMS_CHANGED) {
            return;
        }
        try {
            collabService.onSaved(event.projectId(), List.of(), event.actingUserId());
        } catch (RuntimeException e) {
            log.warn("Collab refresh broadcast failed for project {}: {}", event.projectId(), e.getMessage());
        }
    }
}
