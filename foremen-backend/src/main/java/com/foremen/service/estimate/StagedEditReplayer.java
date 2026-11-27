package com.foremen.service.estimate;

import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;

/**
 * Package-visible entry to the staged-edit replay of {@link EstimateAssignmentService}
 * (FOR-05-04-estimate-AI task 3.2). The preview and Save paths call the same core
 * ({@code replayStagedEdits}); this collaborator lets the scratch service replay edits onto an
 * in-transaction estimate without duplicating the logic.
 */
@Component
class StagedEditReplayer {

    private final EstimateAssignmentService assignmentService;

    StagedEditReplayer(EstimateAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /**
     * Replays {@code edits} in order onto {@code estimate}; persists nothing beyond the caller's transaction.
     * Runs with {@link ReplayOrigin#AI} (FOR-05-04): the only caller is the AI scratch service, so the
     * seeded AI markers survive the replay and the overlay sets the new ones.
     */
    void replay(EstimateEntity estimate, Long projectId, List<StagedEdit> edits) {
        assignmentService.replayStagedEdits(estimate, projectId, edits, ReplayOrigin.AI);
    }
}
