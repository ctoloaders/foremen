package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.document.CompanyProfileDto;
import com.foremen.service.document.CompanyProfileService;

/** FOR-11-06 task 3.4 (R4.4): fallback chain project, company, none. */
class EffectiveBankAccountResolverTest {

    private static final String PROJECT_IBAN = "PL61109010140000071219812874";
    private static final String COMPANY_IBAN = "DE89370400440532013000";

    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final CompanyProfileService companyService = mock(CompanyProfileService.class);
    private final EffectiveBankAccountResolver resolver =
            new EffectiveBankAccountResolver(projectDao, companyService);

    private void company(String account) {
        when(companyService.get()).thenReturn(
                new CompanyProfileDto(1L, "Foremen", "Addr", "525", null, null, null, null, account));
    }

    private ProjectEntity project(String account) {
        ProjectEntity p = new ProjectEntity();
        p.setId(5L);
        p.setBankAccount(account);
        when(projectDao.findById(5L)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void byIdUsesProjectAccountTrimmed() {
        company(COMPANY_IBAN);
        project("  " + PROJECT_IBAN + " ");
        assertThat(resolver.forProject(5L)).isEqualTo(PROJECT_IBAN);
    }

    @Test
    void byIdFallsBackToCompanyWhenProjectHasNone() {
        company(" " + COMPANY_IBAN + " ");
        project(null);
        assertThat(resolver.forProject(5L)).isEqualTo(COMPANY_IBAN);
    }

    @Test
    void unknownOrNullProjectIdUsesCompanyAccount() {
        company(COMPANY_IBAN);
        when(projectDao.findById(99L)).thenReturn(Optional.empty());
        assertThat(resolver.forProject(99L)).isEqualTo(COMPANY_IBAN);
        assertThat(resolver.forProject((Long) null)).isEqualTo(COMPANY_IBAN);
        assertThat(resolver.forProject((ProjectEntity) null)).isEqualTo(COMPANY_IBAN);
    }

    @Test
    void nullWhenNeitherAccountNorCompanyProfileExists() {
        project("");
        when(companyService.get()).thenReturn(null);
        assertThat(resolver.forProject(5L)).isNull();
        company(null);
        assertThat(resolver.forProject(5L)).isNull();
    }
}
