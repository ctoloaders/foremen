package com.foremen.controller.model;

import java.util.List;

public record RolePermissionResponse(Long roleId, List<PermissionEntryResponse> permissions) {}
