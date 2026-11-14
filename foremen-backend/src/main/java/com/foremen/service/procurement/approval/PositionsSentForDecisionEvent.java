package com.foremen.service.procurement.approval;

import java.util.List;

/** FOR-11-02 R6.6: one per send operation; consumed AFTER_COMMIT by the notification emitter (R15). */
public record PositionsSentForDecisionEvent(Long projectId, List<Long> positionIds) {}
