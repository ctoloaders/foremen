package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankAccountType;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code BANK_ACCOUNTS}: bank and cash accounts with balances. The required resource is
 * {@code BANK_CONNECTIONS}, verified against the existing {@code GET /api/bank/accounts} endpoint
 * ({@code BankAccountController} is guarded by {@code @PermissionResource("BANK_CONNECTIONS")}),
 * not {@code BANK_LEDGER} as the design assumed. IBAN, account number, holder data and external ids
 * are not exposed.
 */
@Component
public class BankAccountsDataset extends MoneyDataset {

    public BankAccountsDataset() {
        super("BANK_ACCOUNTS", "BANK_CONNECTIONS", BankAccountEntity.class, "balanceUpdatedAt", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("name", FieldType.STRING),
                enumField("type", BankAccountType.class),
                FieldDescriptor.of("nature", FieldType.STRING),
                FieldDescriptor.of("currency", FieldType.STRING),
                money("balance", "balance", "currency"),
                money("availableBalance", "availableBalance", "currency"),
                money("openingBalance", "openingBalance", "currency"),
                FieldDescriptor.of("archived", FieldType.BOOLEAN),
                FieldDescriptor.of("includedInLedger", FieldType.BOOLEAN),
                FieldDescriptor.of("hasBalanceGap", FieldType.BOOLEAN),
                FieldDescriptor.of("balanceUpdatedAt", FieldType.DATETIME),
                FieldDescriptor.of("connection.bankName", FieldType.STRING)));
    }
}
