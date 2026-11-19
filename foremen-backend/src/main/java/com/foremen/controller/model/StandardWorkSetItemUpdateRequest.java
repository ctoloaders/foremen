package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

public record StandardWorkSetItemUpdateRequest(@NotNull Long roomTypeId, @NotNull Long workItemId) {}
