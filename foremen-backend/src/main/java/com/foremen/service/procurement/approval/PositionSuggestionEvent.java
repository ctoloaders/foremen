package com.foremen.service.procurement.approval;

/** FOR-11-02 R10: the client supplied an own link for a position. */
public record PositionSuggestionEvent(Long projectId, Long positionId) {}
