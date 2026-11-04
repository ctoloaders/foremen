package com.foremen.service.bank.sync;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankSyncRunAccountEntity;
import com.foremen.dao.model.BankSyncRunEntity;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.dao.model.SyncTrigger;

/**
 * FOR-07-04 (Requirements 5.1, 19.6) — read model of a sync run ("Sync history" tab).
 *
 * @param accounts per-account breakdown; {@code null} in list responses, filled on the detail
 */
public record BankSyncRunDto(
        Long id,
        Long connectionId,
        String connectionName,
        String bankName,
        SyncTrigger trigger,
        SyncRunStatus status,
        LocalDateTime createdDate,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String stage,
        int accountsSeen,
        int transactionsNew,
        int transactionsUpdated,
        int pendingReplaced,
        int rowsSkipped,
        int toReview,
        String errorClass,
        String errorCode,
        String errorMessage,
        String requestedBy,
        List<AccountBreakdown> accounts) {

    /** One account's slice of a run. */
    public record AccountBreakdown(
            Long id,
            Long accountId,
            String accountName,
            String iban,
            String currency,
            SyncRunStatus status,
            int transactionsNew,
            int transactionsUpdated,
            int pendingReplaced,
            String errorCode) {

        static AccountBreakdown of(BankSyncRunAccountEntity e) {
            var a = e.getAccount();
            return new AccountBreakdown(e.getId(), a == null ? null : a.getId(),
                    a == null ? null : a.getName(), a == null ? null : a.getIban(),
                    a == null ? null : a.getCurrency(), e.getStatus(), e.getTransactionsNew(),
                    e.getTransactionsUpdated(), e.getPendingReplaced(), e.getErrorCode());
        }
    }

    static BankSyncRunDto of(BankSyncRunEntity r, List<AccountBreakdown> accounts) {
        BankConnectionEntity c = r.getConnection();
        return new BankSyncRunDto(
                r.getId(),
                c == null ? null : c.getId(),
                c == null ? null : c.getDisplayName(),
                c == null ? null : c.getBankName(),
                r.getTrigger(),
                r.getStatus(),
                r.getCreatedDate(),
                r.getStartedAt(),
                r.getFinishedAt(),
                r.getStage(),
                r.getAccountsSeen(),
                r.getTransactionsNew(),
                r.getTransactionsUpdated(),
                r.getPendingReplaced(),
                r.getRowsSkipped(),
                r.getToReview(),
                r.getErrorClass(),
                r.getErrorCode(),
                r.getErrorMessage(),
                r.getRequestedBy(),
                accounts == null ? null : List.copyOf(accounts));
    }
}
