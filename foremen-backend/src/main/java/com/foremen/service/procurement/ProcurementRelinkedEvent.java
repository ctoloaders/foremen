package com.foremen.service.procurement;

/**
 * FOR-07-03 (Requirement 12.2) - raised by {@code DocumentRelinkService} after a document has been
 * assigned to a project by ADMIN/FINANCIER; consumed by {@link ProcurementRelinkNotificationEmitter}
 * after commit.
 *
 * @param documentId     the relinked document
 * @param uploaderUserId the document's uploader (notification recipient), may be {@code null}
 * @param actorUserId    the user who performed the relink
 * @param projectId      the project the document now belongs to
 * @param projectName    the project name (notification body)
 * @param supplier       the document supplier (body; may be blank)
 * @param invoiceNumber  the invoice number (body fallback; may be blank)
 */
public record ProcurementRelinkedEvent(Long documentId, Long uploaderUserId, Long actorUserId,
                                       Long projectId, String projectName, String supplier,
                                       String invoiceNumber) {
}
