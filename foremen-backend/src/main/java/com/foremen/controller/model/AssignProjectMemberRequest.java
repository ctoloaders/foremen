package com.foremen.controller.model;

import jakarta.validation.constraints.NotNull;

public record AssignProjectMemberRequest(
        @NotNull Long userId,
        @NotNull Long projectId,
        @NotNull Long projectRoleId
) {}
