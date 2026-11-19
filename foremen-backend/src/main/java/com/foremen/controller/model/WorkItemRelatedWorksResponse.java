package com.foremen.controller.model;

import java.util.List;

/** Related-work ids of a work (works added together with it in the estimate); never null, possibly empty. */
public record WorkItemRelatedWorksResponse(List<Long> relatedWorkIds) {
}
