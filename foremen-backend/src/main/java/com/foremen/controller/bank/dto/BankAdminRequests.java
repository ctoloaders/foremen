package com.foremen.controller.bank.dto;

import java.util.Map;

import com.foremen.dao.model.FeedProvider;

/**
 * FOR-07-04 — request bodies of the bank administration endpoints.
 */
public final class BankAdminRequests {

    private BankAdminRequests() {
    }

    /** {@code POST /bank/connections} ("Add bank", R1.3). */
    public record CreateConnection(FeedProvider provider, String providerBankCode, String displayName) {
    }

    /** {@code POST /bank/connections/{id}/complete}: the query params of the return route (R1.4). */
    public record CompleteConnection(Map<String, String> params) {
    }

    /** {@code PATCH /bank/connections/{id}} (rename, R1.7). */
    public record RenameConnection(String displayName) {
    }

    /** {@code PUT /bank/connections/{id}/credentials}: write-only credential values (R2.3). */
    public record Credentials(Map<String, String> values) {
        /** Never prints the values. */
        @Override
        public String toString() {
            return "Credentials[fields=" + (values == null ? 0 : values.size()) + "]";
        }
    }

    /**
     * {@code PATCH /bank/accounts/{id}} (R3.3, R3.8).
     *
     * @param holderOverrideReason required to include a {@code MISMATCH} account
     */
    public record UpdateAccount(Boolean includedInLedger, String holderOverrideReason) {
    }

    /** {@code POST /bank/balance-gaps/{id}/acknowledge} (R7.3). */
    public record AcknowledgeGap(String note) {
    }
}
