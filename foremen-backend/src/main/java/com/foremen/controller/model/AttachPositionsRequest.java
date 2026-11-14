package com.foremen.controller.model;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/** Body of {@code POST /api/purchase-requests/{id}/positions}. */
public record AttachPositionsRequest(@NotEmpty List<Long> positionIds) {}
