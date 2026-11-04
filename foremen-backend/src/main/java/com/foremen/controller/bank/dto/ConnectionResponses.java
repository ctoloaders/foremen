package com.foremen.controller.bank.dto;

import java.time.OffsetDateTime;

/**
 * FOR-07-04 (Requirements 1.3–1.6, 5.1) — response bodies of the connection lifecycle actions.
 */
public final class ConnectionResponses {

    private ConnectionResponses() {
    }

    /** {@code POST /connections}, {@code /authorize}, {@code /reconnect}: the URL the user is sent to. */
    public record Authorization(BankConnectionDto connection, String authorizationUrl) {
    }

    /**
     * {@code POST /connections/{id}/complete}.
     *
     * @param completed {@code true} when this call moved the connection into a healthy status
     * @param syncRunId the {@code CONNECT} run requested, when any
     */
    public record Completion(BankConnectionDto connection, boolean completed, Long syncRunId) {
    }

    /**
     * {@code POST /connections/{id}/refresh}.
     *
     * @param authorizationUrl      widget URL when the provider needs the user (SCA); nullable
     * @param nextRefreshPossibleAt the earliest next refresh; nullable
     * @param syncRunId             the {@code MANUAL} run requested when no user interaction is needed
     */
    public record Refresh(BankConnectionDto connection, String authorizationUrl,
                          OffsetDateTime nextRefreshPossibleAt, Long syncRunId) {
    }

    /** {@code 202} body of {@code POST /connections/{id}/sync}. */
    public record SyncRequested(Long runId) {
    }
}
