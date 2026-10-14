package com.foremen.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.signing.merge.ClientResolver;
import com.foremen.service.signing.merge.MergeContext;
import com.foremen.service.signing.merge.MergeFieldResolver;
import com.foremen.service.signing.merge.PropertyResolver;
import com.foremen.service.signing.merge.ScheduleResolver;

/**
 * Unit test for {@link ProjectTemplateViewService} (FOR-05-08 template placeholder values). It
 * verifies the service reuses the REAL merge resolvers to resolve project-backed tokens and packs
 * them into the nested DTO + flat {@code tokens} map (resolved tokens only). The
 * {@link MergeContextFactory} is mocked so the test drives a known context without a DB.
 */
@ExtendWith(MockitoExtension.class)
class ProjectTemplateViewServiceTest {

    private static final Long PROJECT_ID = 42L;

    @Mock
    private ProjectDao projectDao;
    @Mock
    private MergeContextFactory mergeContextFactory;

    private ProjectTemplateViewService service;

    @BeforeEach
    void setUp() {
        // Use the REAL resolvers for the groups this test exercises, so the mapping is the same one
        // document generation uses (no duplicated logic).
        List<MergeFieldResolver> resolvers = List.of(
                new ClientResolver(), new ScheduleResolver(), new PropertyResolver());
        service = new ProjectTemplateViewService(projectDao, mergeContextFactory, resolvers);
    }

    @Test
    @DisplayName("resolves project-backed tokens into the nested DTO + flat tokens map")
    void resolvesProjectTokens() {
        ProjectEntity project = new ProjectEntity();
        project.setFormattedAddress("Jana Kazimierza 64a, Warszawa");
        project.setStartDate(LocalDate.of(2026, 9, 13));
        // no end date -> {Closedate} unresolved
        project.setArea(new java.math.BigDecimal("83.40"));

        UserEntity client = new UserEntity();
        client.setName("Oleg Goncharenko");
        client.setEmail("oleg@example.com");

        MergeContext ctx = MergeContext.builder()
                .document(new SignableDocumentEntity())
                .project(project)
                .clientMembers(List.of(client))
                .build();

        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        // build(project, <any transient placeholder document>) — match on the project arg, any document.
        when(mergeContextFactory.build(org.mockito.ArgumentMatchers.eq(project),
                org.mockito.ArgumentMatchers.any(SignableDocumentEntity.class))).thenReturn(ctx);

        ProjectTemplateViewDTO view = service.getView(PROJECT_ID);

        assertThat(view.projectId()).isEqualTo(PROJECT_ID);
        // client group
        assertThat(view.client().name()).isEqualTo("Oleg Goncharenko");
        assertThat(view.client().lastName()).isEqualTo("Goncharenko");
        assertThat(view.client().email()).isEqualTo("oleg@example.com");
        assertThat(view.client().address()).isEqualTo("Jana Kazimierza 64a, Warszawa");
        // property group
        assertThat(view.property().worksAddress()).isEqualTo("Jana Kazimierza 64a, Warszawa");
        assertThat(view.property().usableArea()).isEqualTo("83.40");
        // schedule group
        assertThat(view.schedule().beginDate()).isEqualTo("2026-09-13");
        assertThat(view.schedule().closeDate()).isNull();
        // flat tokens map: resolved only, so {Closedate} is absent and no empty values leak
        assertThat(view.tokens())
                .containsEntry("ContactName", "Oleg Goncharenko")
                .containsEntry("UfCrm1662541716442", "Jana Kazimierza 64a, Warszawa")
                .containsEntry("Begindate", "2026-09-13")
                .doesNotContainKey("Closedate")
                .doesNotContainKey("TotalBeforeTax");
        assertThat(view.tokens().values()).noneMatch(v -> v == null || v.isBlank());
    }

    @Test
    @DisplayName("404 when the project does not exist")
    void missingProjectIs404() {
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getView(PROJECT_ID))
                .isInstanceOf(ForemenApiException.class);
    }
}
