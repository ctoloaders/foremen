package com.foremen.service.procurement;

import org.springframework.stereotype.Component;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.service.offer.NotificationService;
import com.foremen.service.permission.ForemenPermissionEvaluator;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-03 Amendment A1 (R17) — emits the single terminal-state in-app notification to the uploader
 * when an asynchronous OCR job reaches {@code PARSED} or {@code FAILED}.
 *
 * <p>Delivery goes through the generic per-recipient {@link NotificationService#create} (FOR-05-07
 * R13) — never a bespoke mechanism. {@code create} runs in its own {@code REQUIRES_NEW} transaction,
 * so it is safe to call from the {@code ProcurementOcrWorker} thread (which runs outside any ambient
 * transaction). Emission is best-effort: a notification failure is logged and swallowed and never
 * changes the document's terminal state (mirrors {@code WorkReportNotificationEmitter}).
 */
@Slf4j
@Component
public class ProcurementOcrNotificationEmitter {

    /** {@code Notification_Type} i18n key: OCR finished and the document parsed (A1 R17.1). */
    static final String TYPE_PARSED = "notification.procurement.ocr.parsed";

    /** {@code Notification_Type} i18n key: OCR failed (A1 R17.2). */
    static final String TYPE_FAILED = "notification.procurement.ocr.failed";

    /** The Procurement tab slug the deep-link targets. */
    private static final String PROCUREMENT_TAB = "procurement";

    /** Company procurement screen deep-link base (FOR-07-03 R12.3). */
    private static final String COMPANY_PROCUREMENT_PATH = "/company/procurement";

    private final NotificationService notificationService;
    private final UserDao userDao;
    private final ForemenPermissionEvaluator permissionEvaluator;

    public ProcurementOcrNotificationEmitter(NotificationService notificationService, UserDao userDao,
                                             ForemenPermissionEvaluator permissionEvaluator) {
        this.notificationService = notificationService;
        this.userDao = userDao;
        this.permissionEvaluator = permissionEvaluator;
    }

    /**
     * Emits the terminal-state notification to the uploader (A1 R17). A non-terminal or
     * {@code UPLOADED} (disabled-provider resting) status emits nothing (A1 R17.4). Best-effort.
     *
     * @param terminalStatus the status the OCR job reached ({@code PARSED} / {@code FAILED})
     * @param recipientUserId the uploader's user id (notification recipient)
     * @param projectId       the owning project id (for the deep-link)
     * @param documentId      the document id (deep-link target)
     * @param failureReason   the failure-reason code on {@code FAILED} (carried as the body), else null
     */
    public void emitTerminal(ProcessingStatus terminalStatus, Long recipientUserId, Long projectId,
                             Long documentId, String failureReason) {
        if (terminalStatus == null || !terminalStatus.isTerminal() || recipientUserId == null) {
            return;
        }
        String type = terminalStatus == ProcessingStatus.PARSED ? TYPE_PARSED : TYPE_FAILED;
        String body = terminalStatus == ProcessingStatus.FAILED ? failureReason : null;
        try {
            String deepLink = deepLink(projectId, documentId,
                    projectId != null || recipientCanReadCompany(recipientUserId));
            notificationService.create(recipientUserId, type, body, deepLink);
        } catch (Exception e) {
            // A1 R17.3 — log-only on failure: never block or alter the committed terminal state.
            log.error("Failed to emit procurement OCR notification (type {}) for document {} to user {}",
                    type, documentId, recipientUserId, e);
        }
    }

    /**
     * The deep-link for a document. Project document: {@code /projects/{projectId}/procurement?doc=}
     * (A1 R17.1). No-project document (FOR-07-03 R12.3): {@code /company/procurement?doc=} when the
     * recipient holds {@code COMPANY_PURCHASES/READ}, else {@code null} (non-clickable notification).
     */
    static String deepLink(Long projectId, Long documentId, boolean recipientCanReadCompany) {
        if (projectId == null) {
            if (!recipientCanReadCompany) {
                return null;
            }
            return documentId == null ? COMPANY_PROCUREMENT_PATH
                    : COMPANY_PROCUREMENT_PATH + "?doc=" + documentId;
        }
        String base = "/projects/" + projectId + "/" + PROCUREMENT_TAB;
        return documentId == null ? base : base + "?doc=" + documentId;
    }

    private boolean recipientCanReadCompany(Long recipientUserId) {
        String roleCode = userDao.findRoleCodeById(recipientUserId).orElse(null);
        return roleCode != null && permissionEvaluator.isAllowed(roleCode, "COMPANY_PURCHASES", "READ");
    }
}
