package com.foremen.service.query;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConsumptionBranch;

/** Resolver for the synthetic {@code finishingConsumptionCount} segment on {@code WorkItemEntity}. */
@Component
public class WorkItemFinishingConsumptionCountQueryResolver
        extends AbstractWorkItemConsumptionCountQueryResolver {

    static final String SEGMENT = "finishingConsumptionCount";

    public WorkItemFinishingConsumptionCountQueryResolver(CustomQueryResolverRegistry registry) {
        super(registry, SEGMENT, ConsumptionBranch.finishing);
    }
}
