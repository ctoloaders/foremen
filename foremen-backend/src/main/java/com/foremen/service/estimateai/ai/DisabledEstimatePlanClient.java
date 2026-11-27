package com.foremen.service.estimateai.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.foremen.service.estimateai.ai.EstimateAiException.Reason;

/**
 * Default wiring when {@code foremen.estimate-ai.enabled} is {@code false}/absent: the application
 * starts normally; any call fails with {@link Reason#DISABLED} ({@code error.estimateai.unavailable}).
 */
@Component
@ConditionalOnProperty(prefix = "foremen.estimate-ai", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class DisabledEstimatePlanClient implements EstimatePlanClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public PlanResponse plan(PlanRequest request) {
        throw new EstimateAiException(Reason.DISABLED, "Estimate AI is disabled");
    }
}
