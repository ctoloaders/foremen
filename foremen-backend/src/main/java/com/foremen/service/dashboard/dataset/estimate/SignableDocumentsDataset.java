package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignatureLevel;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/**
 * Signable documents. Document URI, content hash and source reference are NOT exposed. The entity's
 * project is mandatory, so no null-project rule is needed.
 */
@Component
public class SignableDocumentsDataset extends AbstractEstimateDataset {

    public SignableDocumentsDataset() {
        super("SIGNABLE_DOCUMENTS", "SIGNABLE_DOCUMENTS", SignableDocumentEntity.class, "project.id",
                "createdDate", build());
    }

    private static List<FieldDescriptor> build() {
        List<FieldDescriptor> f = new ArrayList<>();
        f.add(FieldDescriptor.of("id", FieldType.ID));
        f.addAll(projectFields("project"));
        f.add(string("documentType.code", "documentType.code"));
        f.add(string("documentType.nameRU", "documentType.nameRU"));
        f.add(string("documentType.namePL", "documentType.namePL"));
        f.add(enumField("status", "status", DocumentStatus.class));
        f.add(string("title", "title"));
        f.add(enumField("signatureLevel", "signatureLevel", SignatureLevel.class));
        f.add(string("templateLocale", "templateLocale"));
        f.add(string("createdByUser.name", "createdByUser.name"));
        f.add(FieldDescriptor.of("createdDate", FieldType.DATETIME));
        return f;
    }
}
