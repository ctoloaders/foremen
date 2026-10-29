package com.foremen.collab;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FOR-05-13 — configuration for the collaborative estimate (presence + shared staged-edit draft),
 * bound from {@code foremen.collab}. Every value has a safe default so profiles without explicit
 * config (tests, integration) start cleanly.
 *
 * @param enabled            master switch; when {@code false} the STOMP endpoint still starts but
 *                           every collaboration operation is a no-op (single-user mode)
 * @param draftTtlDays       how long an untouched shared draft survives in Redis
 * @param presenceTtlSeconds a presence entry older than this (no heartbeat) is treated as gone
 * @param maxDraftEdits      upper bound on staged edits held in one shared draft
 */
@ConfigurationProperties(prefix = "foremen.collab")
public record CollabProperties(
        Boolean enabled,
        Integer draftTtlDays,
        Integer presenceTtlSeconds,
        Integer maxDraftEdits) {

    public CollabProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (draftTtlDays == null || draftTtlDays <= 0) {
            draftTtlDays = 7;
        }
        if (presenceTtlSeconds == null || presenceTtlSeconds <= 0) {
            presenceTtlSeconds = 45;
        }
        if (maxDraftEdits == null || maxDraftEdits <= 0) {
            maxDraftEdits = 2000;
        }
    }
}
