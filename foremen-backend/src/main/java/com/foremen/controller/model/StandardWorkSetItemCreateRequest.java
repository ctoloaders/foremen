package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

public record StandardWorkSetItemCreateRequest(@NotNull Long roomTypeId, @NotNull Long workItemId) {}
