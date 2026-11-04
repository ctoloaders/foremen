package com.foremen.controller.bank;

import java.util.List;

import com.foremen.controller.bank.dto.BalanceGapDto;
import com.foremen.controller.bank.dto.BankAccountDto;
import com.foremen.controller.bank.dto.BankConnectionDto;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.dao.model.BankBalanceGapEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.HolderMatch;
import com.foremen.service.bank.account.BankAccountService;
import com.foremen.service.bank.cash.CashAccountDto;
import com.foremen.service.bank.secret.BankConnectionSecretService.CredentialsStatus;

/**
 * FOR-07-04 — entity → read-model mapping for the bank administration controllers. Only whitelisted,
 * non-secret fields are copied (R21.6); the connection of an account is passed in explicitly so a
 * lazy association is never initialized here.
 */
final class BankAdminDtoMapper {

    private BankAdminDtoMapper() {
    }

    static BankConnectionDto connection(BankConnectionEntity c, int accountsCount, boolean credentialsRequired,
                                        CredentialsStatus credentials, List<BankAccountDto> accounts) {
        CredentialsStatus cs = credentials == null ? CredentialsStatus.NOT_SET : credentials;
        return new BankConnectionDto(
                c.getId(),
                c.getProvider(),
                c.getProviderMode(),
                c.getProviderCode(),
                c.getBankName(),
                c.getBankLogoUrl(),
                c.getDisplayName(),
                c.getStatus(),
                c.getConsentExpiresAt(),
                c.getLastSyncAt(),
                c.getLastSuccessfulSyncAt(),
                c.getNextRefreshPossibleAt(),
                c.getLastErrorCode(),
                c.getLastErrorMessage(),
                c.getExternalConnectionId(),
                accountsCount,
                credentialsRequired,
                cs.credentialsSet(),
                cs.credentialsUpdatedAt(),
                c.getCreatedDate(),
                accounts == null ? null : List.copyOf(accounts));
    }

    /** A {@code BANK} account; {@code connection} supplies the bank name / logo (nullable). */
    static BankAccountDto bankAccount(BankAccountEntity a, BankConnectionEntity connection) {
        Long connectionId = connection != null ? connection.getId()
                : a.getConnection() == null ? null : a.getConnection().getId();
        return new BankAccountDto(
                a.getId(),
                a.getType() == null ? BankAccountType.BANK : a.getType(),
                connectionId,
                connection == null ? null : connection.getBankName(),
                connection == null ? null : connection.getBankLogoUrl(),
                a.getName(),
                a.getNature(),
                a.getHolderName(),
                a.getHolderMatch(),
                BankAccountService.holderWarning(a),
                a.getHolderOverrideBy(),
                a.getHolderOverrideAt(),
                a.getHolderOverrideReason(),
                a.getIban(),
                a.getAccountNumber(),
                a.getCurrency(),
                a.getBalance(),
                a.getAvailableBalance(),
                a.getBalanceUpdatedAt(),
                a.getOpeningBalance(),
                a.isIncludedInLedger(),
                a.isClosedAtProvider(),
                a.isHasBalanceGap(),
                a.isDefaultForCash(),
                a.isArchived());
    }

    /** A {@code CASH} register with its computed balance (R27.1). */
    static BankAccountDto cashAccount(CashAccountDto c) {
        return new BankAccountDto(
                c.id(),
                BankAccountType.CASH,
                null,
                null,
                null,
                c.name(),
                null,
                null,
                HolderMatch.UNKNOWN,
                false,
                null,
                null,
                null,
                null,
                null,
                c.currency(),
                c.balance(),
                null,
                null,
                c.openingBalance(),
                true,
                false,
                false,
                c.defaultForCash(),
                c.archived());
    }

    static BalanceGapDto gap(BankBalanceGapEntity g, Long accountId, boolean accountHasGap) {
        return new BalanceGapDto(
                g.getId(),
                accountId,
                g.getPeriodFrom(),
                g.getPeriodTo(),
                g.getExpected(),
                g.getActual(),
                g.getDifference(),
                g.getAcknowledgedAt(),
                g.getAcknowledgedBy(),
                g.getNote(),
                g.getCreatedDate(),
                accountHasGap);
    }
}
