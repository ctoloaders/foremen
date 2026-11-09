package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.dao.model.SignatureLevel;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Company-wide catalog of signable document types. */
@Component
public class SignableDocumentTypesDataset extends AbstractEstimateDataset {

    public SignableDocumentTypesDataset() {
        super("SIGNABLE_DOCUMENT_TYPES", "SIGNABLE_DOCUMENT_TYPES", SignableDocumentTypeEntity.class,
                null, null, List.of(
                        FieldDescriptor.of("id", FieldType.ID),
                        string("code", "code"),
                        string("nameRU", "nameRU"),
                        string("namePL", "namePL"),
                        FieldDescriptor.of("active", FieldType.BOOLEAN).withFlags(true, true, true, false),
                        enumField("defaultSignatureLevel", "defaultSignatureLevel", SignatureLevel.class)));
    }
}
