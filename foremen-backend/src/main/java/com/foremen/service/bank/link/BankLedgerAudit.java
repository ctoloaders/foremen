package com.foremen.service.bank.link;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;

/**
 * FOR-07-04 (Requirement 16.10) — audit rows for ledger link / suggestion / ignore / rule mutations,
 * written in the caller's transaction (same pattern as {@code BankConnectionSecretService}).
 */
@Component
public class BankLedgerAudit {

    static final String SYSTEM_USER = "SYSTEM";
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final AuditLogDao auditLogDao;

    public BankLedgerAudit(AuditLogDao auditLogDao) {
        this.auditLogDao = auditLogDao;
    }

    /** One audit row; {@code before} / {@code after} may be {@code null}. */
    public void write(Class<?> entityClass, Long entityId, String operation,
                      Map<String, Object> before, Map<String, Object> after) {
        AuditLogEntity row = new AuditLogEntity();
        row.setEntityClass(entityClass.getSimpleName());
        row.setEntityId(entityId);
        row.setOperation(operation);
        row.setPerformedBy(currentUser());
        row.setPerformedAt(LocalDateTime.now());
        row.setSnapshotBefore(before == null ? null : snapshot(before));
        row.setSnapshotAfter(after == null ? null : snapshot(after));
        auditLogDao.save(row);
    }

    /** The audited fields of a link (ids only, never entity graphs). */
    public static Map<String, Object> linkSnapshot(BankTransactionLinkEntity link) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", link.getId());
        m.put("transactionId", link.getTransaction() == null ? null : link.getTransaction().getId());
        m.put("kind", link.getKind() == null ? null : link.getKind().name());
        m.put("amount", link.getAmount() == null ? null : link.getAmount().toPlainString());
        m.put("workerPayoutId", link.getWorkerPayout() == null ? null : link.getWorkerPayout().getId());
        m.put("procurementDocumentId",
                link.getProcurementDocument() == null ? null : link.getProcurementDocument().getId());
        m.put("paymentScheduleId", link.getPaymentSchedule() == null ? null : link.getPaymentSchedule().getId());
        m.put("paymentTransactionId",
                link.getPaymentTransaction() == null ? null : link.getPaymentTransaction().getId());
        m.put("projectId", link.getProject() == null ? null : link.getProject().getId());
        m.put("unplannedCategoryId",
                link.getUnplannedCategory() == null ? null : link.getUnplannedCategory().getId());
        m.put("counterAccountId", link.getCounterAccount() == null ? null : link.getCounterAccount().getId());
        m.put("source", link.getSource() == null ? null : link.getSource().name());
        m.put("confirmed", link.getConfirmedAt() != null);
        m.put("note", link.getNote());
        return m;
    }

    /** The authenticated user name, or {@code SYSTEM} for background work. */
    public static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return SYSTEM_USER;
    }

    private static String snapshot(Map<String, Object> fields) {
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
