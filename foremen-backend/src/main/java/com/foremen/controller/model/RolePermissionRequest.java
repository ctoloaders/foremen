package com.foremen.controller.model;

import java.util.List;

public record RolePermissionRequest(List<PermissionEntryRequest> permissions) {}
