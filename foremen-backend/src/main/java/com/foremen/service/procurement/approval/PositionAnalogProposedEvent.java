package com.foremen.service.procurement.approval;

/** FOR-11-02 R9: a manager proposed an analog (or a package choice) for a position. */
public record PositionAnalogProposedEvent(Long projectId, Long positionId) {}
