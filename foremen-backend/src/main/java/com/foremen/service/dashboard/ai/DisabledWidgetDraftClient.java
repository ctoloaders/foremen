package com.foremen.service.dashboard.ai;

import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default wiring when {@code foremen.dashboard.ai.enabled} is {@code false}/absent: the application
 * starts normally; any call fails with {@link Reason#DISABLED}.
 */
@Component
@ConditionalOnProperty(prefix = "foremen.dashboard.ai", name = "enabled", havingValue = "false",
        matchIfMissing = true)
public class DisabledWidgetDraftClient implements WidgetDraftClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public DraftResponse draft(DraftRequest request) {
        throw new WidgetDraftException(Reason.DISABLED, "Dashboard AI assistant is disabled");
    }
}
