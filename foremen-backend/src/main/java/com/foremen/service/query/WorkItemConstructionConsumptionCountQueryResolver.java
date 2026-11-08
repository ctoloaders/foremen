package com.foremen.service.query;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ConsumptionBranch;

/** Resolver for the synthetic {@code constructionConsumptionCount} segment on {@code WorkItemEntity}. */
@Component
public class WorkItemConstructionConsumptionCountQueryResolver
        extends AbstractWorkItemConsumptionCountQueryResolver {

    static final String SEGMENT = "constructionConsumptionCount";

    public WorkItemConstructionConsumptionCountQueryResolver(CustomQueryResolverRegistry registry) {
        super(registry, SEGMENT, ConsumptionBranch.construction);
    }
}
