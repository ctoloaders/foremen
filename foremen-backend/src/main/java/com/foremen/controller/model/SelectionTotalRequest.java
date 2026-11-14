package com.foremen.controller.model;

import java.util.List;

/**
 * Body of {@code POST /api/purchase-positions/selection-total} (FOR-11-01 R6.1). The 1..500 size limits
 * are checked by the service so they return the dedicated 400 keys.
 */
public record SelectionTotalRequest(List<Long> positionIds) {}
