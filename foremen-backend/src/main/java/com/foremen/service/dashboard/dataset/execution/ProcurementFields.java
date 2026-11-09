package com.foremen.service.dashboard.dataset.execution;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Field catalogs shared by the project and company procurement datasets. Object keys, supplier NIP,
 * invoice number, failure reason and line manufacturer/unit hints are deliberately not exposed.
 */
final class ProcurementFields {

    private ProcurementFields() {
    }

    /** {@code documentDate}: invoice date, falling back to the upload date. */
    private static FieldDescriptor documentDate(String name, String prefix) {
        return FieldDescriptor.computed(name, FieldType.DATE, (cb, q, root) -> {
            Path<?> doc = prefix.isEmpty() ? root : root.get(prefix);
            Expression<LocalDate> invoice = doc.get("invoiceDate");
            Expression<LocalDate> uploaded = doc.<OffsetDateTime>get("uploadedAt").as(LocalDate.class);
            return cb.coalesce(invoice, uploaded);
        });
    }

    static List<FieldDescriptor> documents() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("supplierName", FieldType.STRING),
                FieldDescriptor.of("documentTotal", FieldType.MONEY),
                FieldDescriptor.of("invoiceDate", FieldType.DATE),
                documentDate("documentDate", ""),
                ExecutionDataset.enumField("status", ProcessingStatus.class),
                FieldDescriptor.of("uploadedAt", FieldType.DATETIME),
                FieldDescriptor.of("uploadedBy.name", FieldType.STRING),
                FieldDescriptor.of("expenseCategory.code", FieldType.STRING),
                FieldDescriptor.of("expenseCategory.nameRU", FieldType.STRING),
                FieldDescriptor.of("expenseCategory.namePL", FieldType.STRING));
    }

    static List<FieldDescriptor> lines() {
        return List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("document.project.id", FieldType.ID),
                FieldDescriptor.of("document.project.name", FieldType.STRING),
                FieldDescriptor.of("document.supplierName", FieldType.STRING),
                documentDate("document.documentDate", "document"),
                FieldDescriptor.of("lineNo", FieldType.NUMBER),
                FieldDescriptor.of("rawName", FieldType.STRING),
                FieldDescriptor.of("quantity", FieldType.NUMBER),
                FieldDescriptor.of("unitPrice", FieldType.MONEY),
                FieldDescriptor.of("lineTotal", FieldType.MONEY),
                ExecutionDataset.enumField("matchStatus", MatchStatus.class),
                FieldDescriptor.of("unplannedCategory.code", FieldType.STRING),
                FieldDescriptor.of("unplannedCategory.nameRU", FieldType.STRING),
                FieldDescriptor.of("unplannedCategory.namePL", FieldType.STRING));
    }
}
