package com.foremen.controller.model;

import java.util.List;

public record BatchRolePermissionEntry(Long roleId, List<PermissionEntryRequest> permissions) {}
