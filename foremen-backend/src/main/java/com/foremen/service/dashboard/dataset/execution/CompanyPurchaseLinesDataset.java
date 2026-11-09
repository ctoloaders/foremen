package com.foremen.service.dashboard.dataset.execution;

import com.foremen.dao.model.ProcurementLineEntity;
import org.springframework.stereotype.Component;

/** {@code COMPANY_PURCHASE_LINES}: lines of every procurement document, company-wide. */
@Component
public class CompanyPurchaseLinesDataset extends ExecutionDataset {

    public CompanyPurchaseLinesDataset() {
        super("COMPANY_PURCHASE_LINES", "COMPANY_PURCHASES", ProcurementLineEntity.class,
                "document.documentDate", ProcurementFields.lines());
    }
}
