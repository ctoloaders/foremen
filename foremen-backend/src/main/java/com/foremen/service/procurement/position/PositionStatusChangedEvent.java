package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

/** FOR-11-00 (R8.6): published by {@code PositionLifecycleService} after a position changes stage. */
public record PositionStatusChangedEvent(Long positionId, PositionStatus from, PositionStatus to, Actor actor) {
}
