package com.foremen.service.procurement.approval;

/**
 * FOR-11-02 R7, R8, R11: a decision on a position round. {@code onBehalf} is true when a manager
 * recorded the decision for the client.
 */
public record PositionDecisionEvent(Long projectId, Long positionId, Outcome outcome, boolean onBehalf,
                                    Long actorUserId) {

    public enum Outcome { ACCEPTED, REJECTED, CLIENT_OWN }
}
