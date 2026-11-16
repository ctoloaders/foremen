package com.foremen.service.procurement.estimatesync;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.service.ProjectActivatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-06 (R5.6): runs the estimate sync after a project becomes ACTIVE. Best effort: any failure is
 * logged and never propagates to the activation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EstimateSyncOnActivation {

    private final EstimateSyncService syncService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProjectActivated(ProjectActivatedEvent event) {
        try {
            EstimateSyncSummary summary = syncService.syncInNewTransaction(event.projectId());
            log.info("Estimate sync after activation of project {}: {}", event.projectId(), summary);
        } catch (RuntimeException e) {
            log.warn("Estimate sync after activation of project {} failed: {}", event.projectId(), e.getMessage());
        }
    }
}
