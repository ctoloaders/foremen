package com.foremen.controller.model;

import java.util.List;

public record BatchRolePermissionRequest(List<BatchRolePermissionEntry> entries) {}
