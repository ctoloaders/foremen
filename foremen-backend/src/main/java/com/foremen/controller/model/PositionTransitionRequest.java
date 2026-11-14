package com.foremen.controller.model;

import com.foremen.dao.model.PositionStatus;

import jakarta.validation.constraints.NotNull;

/** FOR-11-00 (R8.7): body of {@code POST /api/purchase-positions/{id}/transition}. */
public record PositionTransitionRequest(@NotNull PositionStatus target) {
}
