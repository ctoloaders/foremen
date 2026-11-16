package com.foremen.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.document.CompanyProfileDto;
import com.foremen.service.document.CompanyProfileService;

/**
 * FOR-11-06 (R4.4): the payee account of a project, in priority order: the project's own account,
 * else the company profile account, else {@code null}.
 */
@Service
public class EffectiveBankAccountResolver {

    private final ProjectDao projectDao;
    private final CompanyProfileService companyProfileService;

    public EffectiveBankAccountResolver(ProjectDao projectDao, CompanyProfileService companyProfileService) {
        this.projectDao = projectDao;
        this.companyProfileService = companyProfileService;
    }

    @Transactional(readOnly = true)
    public String forProject(Long projectId) {
        ProjectEntity project = projectId == null ? null : projectDao.findById(projectId).orElse(null);
        return forProject(project);
    }

    /** Variant for callers that already hold the project; a {@code null} project uses the company account. */
    @Transactional(readOnly = true)
    public String forProject(ProjectEntity project) {
        if (project != null && !isBlank(project.getBankAccount())) {
            return project.getBankAccount().trim();
        }
        CompanyProfileDto company = companyProfileService.get();
        return company == null || isBlank(company.bankAccount()) ? null : company.bankAccount().trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
