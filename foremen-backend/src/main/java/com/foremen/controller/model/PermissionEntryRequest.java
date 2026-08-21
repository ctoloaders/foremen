package com.foremen.controller.model;

import java.util.List;

public record PermissionEntryRequest(Long resourceId, List<Long> operationIds) {}
