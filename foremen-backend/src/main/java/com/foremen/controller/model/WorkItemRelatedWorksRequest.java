package com.foremen.controller.model;

import java.util.List;

/** Request body for {@code PUT /api/work-items/{id}/related-works}: the full desired set (replace; empty clears). */
public record WorkItemRelatedWorksRequest(List<Long> relatedWorkIds) {
}
