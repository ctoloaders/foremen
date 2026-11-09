package com.foremen.service.dashboard.dataset.execution;

import com.foremen.dao.model.ProcurementDocumentEntity;
import org.springframework.stereotype.Component;

/** {@code COMPANY_PURCHASES}: every procurement document, company-wide (no project scope). */
@Component
public class CompanyPurchasesDataset extends ExecutionDataset {

    public CompanyPurchasesDataset() {
        super("COMPANY_PURCHASES", "COMPANY_PURCHASES", ProcurementDocumentEntity.class, "documentDate",
                ProcurementFields.documents());
    }
}
