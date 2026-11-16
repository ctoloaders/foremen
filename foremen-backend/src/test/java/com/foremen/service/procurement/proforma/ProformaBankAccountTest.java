package com.foremen.service.procurement.proforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EffectiveBankAccountResolver;
import com.foremen.service.document.CompanyProfileDto;
import com.foremen.service.document.CompanyProfileService;

/** FOR-11-06 task 3.3 (R4.4 - R4.7): effective account, snapshot and relaxed COMPANY_INCOMPLETE. */
class ProformaBankAccountTest {

    private static final String PROJECT_IBAN = "PL61109010140000071219812874";
    private static final String COMPANY_IBAN = "DE89370400440532013000";

    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final CompanyProfileService companyService = mock(CompanyProfileService.class);
    private final EffectiveBankAccountResolver resolver =
            new EffectiveBankAccountResolver(projectDao, companyService);

    private void company(String name, String address, String nip, String account) {
        when(companyService.get()).thenReturn(
                new CompanyProfileDto(1L, name, address, nip, null, null, null, null, account));
    }

    private static ProjectEntity project(String account) {
        ProjectEntity p = new ProjectEntity();
        p.setId(5L);
        p.setName("Project");
        p.setBankAccount(account);
        return p;
    }

    @Test
    void projectAccountWinsOverCompanyAccount() {
        company("Foremen", "Addr", "525", COMPANY_IBAN);
        assertThat(resolver.forProject(project(PROJECT_IBAN))).isEqualTo(PROJECT_IBAN);
    }

    @Test
    void companyAccountIsTheFallbackAndNullWhenNeitherExists() {
        company("Foremen", "Addr", "525", COMPANY_IBAN);
        assertThat(resolver.forProject(project(null))).isEqualTo(COMPANY_IBAN);
        company("Foremen", "Addr", "525", " ");
        assertThat(resolver.forProject(project(" "))).isNull();
    }

    @Test
    void snapshotStripsSpacesAndSkipsOverlongValues() {
        assertThat(ProformaIssueService.snapshotOf("PL61 1090 1014 0000 0712 1981 2874"))
                .isEqualTo(PROJECT_IBAN);
        assertThat(ProformaIssueService.snapshotOf("x".repeat(40))).isNull();
        assertThat(ProformaIssueService.snapshotOf(null)).isNull();
    }

    private ProformaPdfService pdfService() {
        ProjectMemberDao members = mock(ProjectMemberDao.class);
        when(members.findByProjectIdWithUserAndRole(any())).thenReturn(List.of());
        return new ProformaPdfService(new ProformaHtmlBuilder(), null, null, companyService, members, resolver);
    }

    private static ClientProformaEntity proforma(ProjectEntity project, String snapshot) {
        ClientProformaEntity pf = new ClientProformaEntity();
        pf.setProject(project);
        pf.getPayment().setBankAccountSnapshot(snapshot);
        return pf;
    }

    @Test
    void modelUsesSnapshotOfAnIssuedPaymentEvenWhenTheProjectAccountChanged() {
        company("Foremen", "Addr", "525", COMPANY_IBAN);
        ProformaDocModel model = pdfService().buildModel(proforma(project(COMPANY_IBAN), PROJECT_IBAN), List.of());
        assertThat(model.sellerBankAccount()).isEqualTo(PROJECT_IBAN);
    }

    @Test
    void previewUsesEffectiveAccountAndProjectAccountSatisfiesTheCheck() {
        company("Foremen", "Addr", "525", null);
        ProformaDocModel model = pdfService().buildModel(proforma(project(PROJECT_IBAN), null), List.of());
        assertThat(model.sellerBankAccount()).isEqualTo(PROJECT_IBAN);
    }

    @Test
    void incompleteWhenNoEffectiveAccountOrCompanyDataMissing() {
        company("Foremen", "Addr", "525", null);
        assertThatThrownBy(() -> pdfService().buildModel(proforma(project(null), null), List.of()))
                .isInstanceOf(ForemenApiException.class);
        company("Foremen", "Addr", " ", COMPANY_IBAN);
        assertThatThrownBy(() -> pdfService().buildModel(proforma(project(PROJECT_IBAN), null), List.of()))
                .isInstanceOf(ForemenApiException.class);
    }
}
