package com.foremen.service.dashboard.dataset.estimate;

import com.foremen.dao.model.DocumentTemplateEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.foremen.service.dashboard.dataset.estimate.EstimateDatasetSupport.*;

/** Document templates. The storage URI is NOT exposed. */
@Component
public class DocumentTemplatesDataset extends AbstractEstimateDataset {

    public DocumentTemplatesDataset() {
        super("DOCUMENT_TEMPLATES", "DOCUMENT_TEMPLATES", DocumentTemplateEntity.class, null,
                "createdDate", List.of(
                        FieldDescriptor.of("id", FieldType.ID),
                        string("documentType.code", "documentType.code"),
                        string("documentType.nameRU", "documentType.nameRU"),
                        string("documentType.namePL", "documentType.namePL"),
                        string("name", "name"),
                        string("locale", "locale"),
                        FieldDescriptor.of("version", FieldType.NUMBER),
                        FieldDescriptor.of("active", FieldType.BOOLEAN).withFlags(true, true, true, false),
                        string("uploadedBy.name", "uploadedBy.name"),
                        FieldDescriptor.of("createdDate", FieldType.DATETIME)));
    }
}
