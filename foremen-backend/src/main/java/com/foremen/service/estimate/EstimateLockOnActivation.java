package com.foremen.service.estimate;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.service.ProjectActivatedEvent;

import lombok.RequiredArgsConstructor;

/**
 * Service-locks the project's estimate once the project becomes ACTIVE (FOR-05-07 R20.2, TC-LOCK-01).
 *
 * <p>The lock is the estimate status moving past {@link EstimateStatus#DRAFT}: {@link DraftGateGuard}
 * then rejects every free-edit write with 409 {@code error.estimate.locked}. The estimate was never moved
 * off DRAFT before, so the lock never engaged (lifecycle BUG-02). The status {@link EstimateStatus#SIGNED}
 * is also the baseline that amendments require (FOR-06-08), so activation makes amendments eligible.
 *
 * <p>Synchronous listener: it runs inside the activation transaction, so the lock commits together with
 * the ACTIVE status.
 */
@Component
@RequiredArgsConstructor
public class EstimateLockOnActivation {

    private final EstimateDao estimateDao;

    @EventListener
    @Transactional
    public void onProjectActivated(ProjectActivatedEvent event) {
        estimateDao.findByProjectId(event.projectId()).ifPresent(this::lock);
    }

    private void lock(EstimateEntity estimate) {
        if (estimate.getStatus() != EstimateStatus.SIGNED) {
            estimate.setStatus(EstimateStatus.SIGNED);
            estimateDao.save(estimate);
        }
    }
}
