package com.foremen.controller.model;

public record ProjectMemberResponse(
        Long id,
        Long userId,
        Long projectId,
        Long projectRoleId,
        String projectRoleCode
) {}
