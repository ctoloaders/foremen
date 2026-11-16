package com.foremen.service.procurement.position;

/**
 * FOR-11-06 (R10.6): a buyer added a position through {@code POST /api/purchase-positions/buyer-added}.
 * Consumed after commit by the notification emitter ({@code notification.position.addedByBuyer} to the
 * project's CLIENT and MANAGER members, actor excluded). Carries no price or purchase data.
 *
 * @param positionId  the created position
 * @param projectId   its project
 * @param actorUserId the creating user (never notified), may be {@code null}
 */
public record PositionAddedByBuyerEvent(Long positionId, Long projectId, Long actorUserId) {
}
