package com.foremen.service.dashboard.ai;

/**
 * FOR-10 (Requirement 13) — drafts a dashboard widget from a free-text description. Implementations:
 * {@link GeminiWidgetDraftClient} (real model), {@link StubWidgetDraftClient} (deterministic, tests and
 * Docker) and {@link DisabledWidgetDraftClient} (feature off). Exactly one is active.
 */
public interface WidgetDraftClient {

    /** {@code false} for the disabled client. */
    boolean isEnabled();

    /**
     * @return a draft whose config passed the config validator
     * @throws WidgetDraftException on provider failure, timeout, disabled feature or a draft that stays
     *                              invalid after the bounded retry
     */
    DraftResponse draft(DraftRequest request);
}
