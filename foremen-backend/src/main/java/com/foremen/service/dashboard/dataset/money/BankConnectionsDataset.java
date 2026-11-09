package com.foremen.service.dashboard.dataset.money;

import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code BANK_CONNECTIONS}: company-wide bank feed connections. External customer/connection/consent
 * ids, holder names, logo url and error messages are not exposed.
 */
@Component
public class BankConnectionsDataset extends MoneyDataset {

    public BankConnectionsDataset() {
        super("BANK_CONNECTIONS", "BANK_CONNECTIONS", BankConnectionEntity.class, "lastSyncAt", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("bankName", FieldType.STRING),
                FieldDescriptor.of("displayName", FieldType.STRING),
                enumField("provider", FeedProvider.class),
                enumField("status", BankConnectionStatus.class),
                FieldDescriptor.of("consentExpiresAt", FieldType.DATETIME),
                FieldDescriptor.of("lastSyncAt", FieldType.DATETIME),
                FieldDescriptor.of("lastSuccessfulSyncAt", FieldType.DATETIME),
                FieldDescriptor.of("lastErrorCode", FieldType.STRING)));
    }
}
