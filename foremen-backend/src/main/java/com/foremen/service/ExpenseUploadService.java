package com.foremen.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.controller.model.ExpenseOptionsDto;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementPaymentChoice;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.permission.ForemenPermissionEvaluator;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * FOR-07-03 (Requirements 7.1-7.6, 10.5) - the shared "create an expense" use case behind
 * {@code /api/expenses}, available to every internal role holding {@code EXPENSE_UPLOAD}.
 *
 * <p>Deliberately <b>not</b> a {@code ProjectScopedService}: the optional project is validated
 * explicitly (R7.3 / R10.5) instead of through the generic project-scope guard, because a no-project
 * expense has no project to scope by. The upload itself is delegated to
 * {@link ProcurementDocumentService#upload(Long, MultipartFile, Long,
 * ProcurementDocumentService.UploadPaymentRequest)} so file validation, OCR enqueue and the FOR-07-04
 * cash handler are unchanged.
 */
@Service
@RequiredArgsConstructor
public class ExpenseUploadService {

    static final String PAYMENT_CHOICE_NOT_ALLOWED = "error.expense.upload.paymentChoiceNotAllowed";
    static final String NOT_FOUND = "error.entity.not.found";

    private static final String EXPENSE_UPLOAD = "EXPENSE_UPLOAD";
    private static final String COMPANY_PURCHASES = "COMPANY_PURCHASES";
    private static final String BANK_LEDGER = "BANK_LEDGER";
    private static final String READ = "READ";

    private final ProcurementDocumentService documentService;
    private final ProjectAccessCache projectAccessCache;
    private final ForemenPermissionEvaluator permissionEvaluator;
    private final CashLedgerService cashLedgerService;
    private final EntityManager entityManager;

    /**
     * Creates an expense (a {@code ProcurementDocument}, project optional).
     *
     * @param file              the receipt / invoice (image or PDF)
     * @param projectId         optional project; a non-ADMIN caller must be a member (else 404)
     * @param expenseCategoryId optional active expense category (else 400)
     * @param payment           {@code NOT_LINKED} or {@code PAID_CASH} (others 400); {@code null} means
     *                          {@code NOT_LINKED}
     * @return the created document read model
     */
    @Transactional
    public ProcurementDocumentServiceExtendedModel create(MultipartFile file, Long projectId,
                                                          Long expenseCategoryId,
                                                          ProcurementDocumentService.UploadPaymentRequest payment) {
        ProcurementDocumentService.UploadPaymentRequest request = payment == null
                ? ProcurementDocumentService.UploadPaymentRequest.NOT_LINKED : payment;
        // R7.2 - ledger-driven choices are not accepted on this endpoint.
        ProcurementPaymentChoice choice = request.effectiveChoice();
        if (choice != ProcurementPaymentChoice.NOT_LINKED && choice != ProcurementPaymentChoice.PAID_CASH) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PAYMENT_CHOICE_NOT_ALLOWED);
        }
        // R7.3 - explicit membership check, indistinguishable from a missing project.
        if (projectId != null && !isAdmin()) {
            Long userId = callerUserId();
            Set<Long> allowed = userId == null ? Set.of() : projectAccessCache.get(userId);
            if (!allowed.contains(projectId)) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, projectId);
            }
        }
        // R7.4 - only callers who may see the ledger can pick a cash account; everyone else gets the
        // default cash account rule (a client-supplied id is ignored).
        Long cashAccountId = request.cashAccountId();
        if (cashAccountId != null && !canChooseCashAccount()) {
            cashAccountId = null;
        }
        ProcurementDocumentService.UploadPaymentRequest effective =
                new ProcurementDocumentService.UploadPaymentRequest(
                        choice, cashAccountId, request.cashAmount(), request.cashDate());
        return documentService.upload(projectId, file, expenseCategoryId, effective);
    }

    /** R7.5 - projects, categories and (when allowed) cash accounts for the upload dialog. */
    @Transactional(readOnly = true)
    public ExpenseOptionsDto options() {
        List<ExpenseOptionsDto.ProjectOption> projects = loadProjects();

        boolean pl = "pl".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
        Comparator<UnplannedExpenseCategoryEntity> byName = Comparator.comparing(
                c -> (pl ? c.getNamePL() : c.getNameRU()).toLowerCase(Locale.ROOT));
        List<ExpenseOptionsDto.CategoryOption> categories = entityManager.createQuery(
                        "select c from UnplannedExpenseCategoryEntity c where c.active = true",
                        UnplannedExpenseCategoryEntity.class)
                .getResultList().stream()
                .sorted(byName)
                .map(c -> new ExpenseOptionsDto.CategoryOption(
                        c.getId(), c.getCode(), c.getNameRU(), c.getNamePL()))
                .toList();

        boolean canChoose = canChooseCashAccount();
        List<ExpenseOptionsDto.CashAccountOption> cash = canChoose
                ? cashLedgerService.listAccounts().stream()
                        .filter(a -> !a.archived())
                        .map(a -> new ExpenseOptionsDto.CashAccountOption(
                                a.id(), a.name(), a.currency(), a.defaultForCash()))
                        .toList()
                : List.of();
        return new ExpenseOptionsDto(projects, categories, canChoose, cash);
    }

    /**
     * R7.6 - lightweight status of an expense for the uploader or a caller with
     * {@code COMPANY_PURCHASES/READ}; anyone else (or a missing id) gets 404.
     */
    @Transactional(readOnly = true)
    public StatusView status(Long id) {
        ProcurementDocumentEntity document = entityManager.find(ProcurementDocumentEntity.class, id);
        if (document == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
        }
        Long userId = callerUserId();
        boolean uploader = userId != null && document.getUploadedBy() != null
                && userId.equals(document.getUploadedBy().getId());
        if (!uploader && !hasPermission(COMPANY_PURCHASES, READ)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
        }
        return new StatusView(document.getId(), document.getStatus(), document.getFailureReason());
    }

    /** Lifecycle projection for the status poll. */
    public record StatusView(Long id, ProcessingStatus status, String failureReason) {
    }

    // --- helpers -------------------------------------------------------------------------------

    private List<ExpenseOptionsDto.ProjectOption> loadProjects() {
        List<ProjectEntity> active = entityManager.createQuery(
                        "select p from ProjectEntity p where p.status = :status order by p.name",
                        ProjectEntity.class)
                .setParameter("status", ProjectStatus.ACTIVE)
                .getResultList();
        Set<Long> allowed = null;
        if (!isAdmin()) {
            Long userId = callerUserId();
            allowed = userId == null ? Set.of() : projectAccessCache.get(userId);
        }
        Set<Long> scope = allowed;
        return active.stream()
                .filter(p -> scope == null || scope.contains(p.getId()))
                .map(p -> new ExpenseOptionsDto.ProjectOption(p.getId(), p.getName()))
                .toList();
    }

    private boolean canChooseCashAccount() {
        return hasPermission(BANK_LEDGER, READ);
    }

    private boolean hasPermission(String resource, String operation) {
        String roleCode = currentRoleCode();
        return roleCode != null && permissionEvaluator.isAllowed(roleCode, resource, operation);
    }

    private static boolean isAdmin() {
        return ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(currentRoleCode());
    }

    /** The caller's role code from the {@code ROLE_<code>} authority (JWT filter convention). */
    private static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith("ROLE_")) {
                return authority.substring("ROLE_".length());
            }
        }
        return null;
    }

    private static Long callerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
