package com.foremen.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.BankLedgerAudit;

/**
 * FOR-11-06 (R4.1, R4.2): sets or clears the payee bank account of a project. This is the only write
 * path of {@code projects.bank_account}; the generic project update ignores the column.
 *
 * <p>Authorization is the service-level half of the contract (the controller adds {@code PROJECTS}
 * UPDATE): the caller must be ADMIN, or an {@code ACTIVE} member of the project whose project role is
 * {@code MANAGER}. Every change writes an audit row whose before / after values are masked to the last
 * four characters.
 */
@Service
public class ProjectBankAccountService {

    static final String NOT_FOUND = "error.entity.not.found";
    static final String FORBIDDEN = "error.project.bankAccount.forbidden";
    static final String MANAGER_ROLE = "MANAGER";
    static final String ADMIN_ROLE = "ADMIN";

    private final ProjectDao projectDao;
    private final ProjectMemberDao projectMemberDao;
    private final BankLedgerAudit audit;

    public ProjectBankAccountService(ProjectDao projectDao,
                                     ProjectMemberDao projectMemberDao,
                                     BankLedgerAudit audit) {
        this.projectDao = projectDao;
        this.projectMemberDao = projectMemberDao;
        this.audit = audit;
    }

    /**
     * Sets (or clears, for a {@code null} / blank value) the project's payee account.
     *
     * @param projectId the project
     * @param rawIban   the submitted IBAN (spaces, case and a bare Polish NRB are normalised)
     * @return the stored normalised IBAN, or {@code null} when cleared
     * @throws ForemenApiException 404 unknown project; 403 {@code error.project.bankAccount.forbidden};
     *                             400 {@code error.project.bankAccount.invalid}
     */
    @Transactional
    public String set(Long projectId, String rawIban) {
        ProjectEntity project = projectId == null ? null : projectDao.findById(projectId).orElse(null);
        if (project == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
        }
        if (!callerMayEdit(projectId)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        String next = rawIban == null || rawIban.isBlank() ? null : IbanValidator.normalize(rawIban);
        String previous = project.getBankAccount();
        project.setBankAccount(next);
        projectDao.save(project);
        audit.write(ProjectEntity.class, projectId, "UPDATE", snapshot(previous), snapshot(next));
        return next;
    }

    /** ADMIN, or an ACTIVE MANAGER member of the project. */
    private boolean callerMayEdit(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && ADMIN_ROLE.equals(
                    authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)) {
                return true;
            }
        }
        Long userId = callerUserId(auth);
        if (userId == null) {
            return false;
        }
        return projectMemberDao.findByUserIdAndProjectId(userId, projectId)
                .filter(m -> m.getAssignmentStatus() == AssignmentStatus.ACTIVE)
                .filter(m -> m.getProjectRole() != null && MANAGER_ROLE.equals(m.getProjectRole().getCode()))
                .isPresent();
    }

    private static Long callerUserId(Authentication auth) {
        String name = auth.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Audit snapshot holding only the masked account. */
    static Map<String, Object> snapshot(String iban) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bankAccount", mask(iban));
        return m;
    }

    /** Masks everything but the last four characters; {@code null} stays {@code null}. */
    static String mask(String iban) {
        if (iban == null) {
            return null;
        }
        if (iban.length() <= 4) {
            return "****";
        }
        return "*".repeat(iban.length() - 4) + iban.substring(iban.length() - 4);
    }
}
