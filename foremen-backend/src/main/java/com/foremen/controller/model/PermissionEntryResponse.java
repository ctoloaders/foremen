package com.foremen.controller.model;

import java.util.List;

public record PermissionEntryResponse(Long resourceId, String resourceCode, String resourceName, List<OperationInfo> operations) {}
