package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ocr.OcrService;
import com.foremen.service.procurement.MatchEngine;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;
import com.foremen.service.procurement.ProcurementRelinkedEvent;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-03 task 4.6 - unit tests for {@link DocumentRelinkService}: validations (R5.2-5.4), re-match of
 * AUTO / UNMATCHED lines, manual preservation and foreign-manual reset (R5.5, R5.6), category re-apply
 * (R3.4), audit (R11.1) and the relinked event (R12.2).
 */
class DocumentRelinkServiceTest {

    private static final Long DOC_ID = 10L;
    private static final Long PROJECT_ID = 5L;

    private final EntityManager em = mock(EntityManager.class);
    private final PlannedMaterialCandidateProvider candidates = mock(PlannedMaterialCandidateProvider.class);
    private final ProcurementDocumentService documentService = mock(ProcurementDocumentService.class);
    private final OcrService ocr = mock(OcrService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final DocumentRelinkService service = new DocumentRelinkService(
            em, new MatchEngine(), candidates, documentService, ocr, publisher);

    private ProjectEntity project;
    private ProcurementDocumentEntity document;

    @BeforeEach
    void setUp() {
        project = project(PROJECT_ID, ProjectStatus.ACTIVE);
        document = new ProcurementDocumentEntity();
        document.setId(DOC_ID);
        document.setStatus(ProcessingStatus.PARSED);
        document.setSupplierName("ACME");
        UserEntity uploader = new UserEntity();
        uploader.setId(3L);
        document.setUploadedBy(uploader);
        when(em.find(ProcurementDocumentEntity.class, DOC_ID)).thenReturn(document);
        when(em.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
        when(candidates.candidatesFor(PROJECT_ID)).thenReturn(List.of(
                new PlannedMaterial(7L, "Holcim", "cement", List.of("Cement Holcim"))));
        when(documentService.auditSnapshot(any())).thenReturn("{}");
    }

    @Test
    void nullProjectIsBadRequest() {
        assertError(() -> service.relink(DOC_ID, null), HttpStatus.BAD_REQUEST,
                "error.companyProcurement.relink.projectRequired");
    }

    @Test
    void unknownDocumentIsNotFound() {
        assertError(() -> service.relink(99L, PROJECT_ID), HttpStatus.NOT_FOUND, "error.entity.not.found");
    }

    @Test
    void unknownOrInactiveProjectIsRejected() {
        assertError(() -> service.relink(DOC_ID, 404L), HttpStatus.NOT_FOUND, "error.entity.not.found");
        project.setStatus(ProjectStatus.DRAFT);
        assertError(() -> service.relink(DOC_ID, PROJECT_ID), HttpStatus.CONFLICT,
                "error.purchases.stage.notActive");
    }

    @Test
    void sameProjectIsConflictAndChangesNothing() {
        document.setProject(project);
        ProcurementLineEntity line = line(1, "Cement Holcim", MatchStatus.UNMATCHED, null, MatchTier.NONE);
        document.getLines().add(line);

        assertError(() -> service.relink(DOC_ID, PROJECT_ID), HttpStatus.CONFLICT,
                "error.companyProcurement.relink.sameProject");
        assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void processingDocumentIsConflict() {
        document.setStatus(ProcessingStatus.OCR_TEXT);
        assertError(() -> service.relink(DOC_ID, PROJECT_ID), HttpStatus.CONFLICT,
                "error.companyProcurement.relink.processing");

        document.setStatus(ProcessingStatus.UPLOADED);
        when(ocr.isEnabled()).thenReturn(true);
        assertError(() -> service.relink(DOC_ID, PROJECT_ID), HttpStatus.CONFLICT,
                "error.companyProcurement.relink.processing");
    }

    @Test
    void uploadedWithOcrDisabledCanBeRelinked() {
        document.setStatus(ProcessingStatus.UPLOADED);
        when(ocr.isEnabled()).thenReturn(false);

        DocumentRelinkService.Result result = service.relink(DOC_ID, PROJECT_ID);

        assertThat(document.getProject()).isSameAs(project);
        assertThat(result.matchedLines()).isZero();
    }

    @Test
    void noProjectDocumentGetsMatchedAndCategoryMovesToUnmatchedLines() {
        UnplannedExpenseCategoryEntity category = new UnplannedExpenseCategoryEntity();
        category.setId(2L);
        document.setExpenseCategory(category);
        ProcurementLineEntity matching = line(1, "Cement Holcim 25kg", MatchStatus.UNMATCHED, null, MatchTier.NONE);
        matching.setUnplannedCategory(category);
        ProcurementLineEntity other = line(2, "Nails", MatchStatus.UNMATCHED, null, MatchTier.NONE);
        document.getLines().addAll(List.of(matching, other));

        DocumentRelinkService.Result result = service.relink(DOC_ID, PROJECT_ID);

        assertThat(document.getProject()).isSameAs(project);
        assertThat(matching.getMatchStatus()).isEqualTo(MatchStatus.AUTO);
        assertThat(matching.getMatchedMaterialId()).isEqualTo(7L);
        assertThat(matching.getUnplannedCategory()).isNull();
        assertThat(other.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
        assertThat(other.getMatchedMaterialId()).isNull();
        assertThat(other.getUnplannedCategory()).isSameAs(category);
        assertThat(result.matchedLines()).isEqualTo(1);
        assertThat(result.resetLines()).isZero();
        verify(documentService).auditUpdate("{}", document);
    }

    @Test
    void autoLineThatNoLongerMatchesBecomesUnmatched() {
        document.setProject(project(6L, ProjectStatus.ACTIVE));
        ProcurementLineEntity stale = line(1, "Paint", MatchStatus.AUTO, 99L, MatchTier.CATEGORY);
        document.getLines().add(stale);

        service.relink(DOC_ID, PROJECT_ID);

        assertThat(stale.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
        assertThat(stale.getMatchedMaterialId()).isNull();
        assertThat(stale.getMatchTier()).isEqualTo(MatchTier.NONE);
    }

    @Test
    void projectChangeResetsForeignManualMatchButKeepsPlannedAndManualUnmatched() {
        document.setProject(project(6L, ProjectStatus.ACTIVE));
        ProcurementLineEntity foreign = line(1, "Paint", MatchStatus.MANUAL_MATCHED, 99L, MatchTier.EXACT);
        ProcurementLineEntity planned = line(2, "Misc", MatchStatus.MANUAL_MATCHED, 7L, MatchTier.EXACT);
        ProcurementLineEntity manualUnmatched = line(3, "Cement Holcim", MatchStatus.MANUAL_UNMATCHED, null,
                MatchTier.NONE);
        document.getLines().addAll(List.of(foreign, planned, manualUnmatched));

        DocumentRelinkService.Result result = service.relink(DOC_ID, PROJECT_ID);

        assertThat(foreign.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
        assertThat(foreign.getMatchedMaterialId()).isNull();
        assertThat(foreign.getMatchTier()).isEqualTo(MatchTier.NONE);
        assertThat(planned.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_MATCHED);
        assertThat(planned.getMatchedMaterialId()).isEqualTo(7L);
        assertThat(manualUnmatched.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_UNMATCHED);
        assertThat(result.resetLines()).isEqualTo(1);
        assertThat(result.matchedLines()).isEqualTo(1);
    }

    @Test
    void manualMatchIsKeptWhenDocumentHadNoProject() {
        ProcurementLineEntity manual = line(1, "Paint", MatchStatus.MANUAL_MATCHED, 99L, MatchTier.EXACT);
        document.getLines().add(manual);

        DocumentRelinkService.Result result = service.relink(DOC_ID, PROJECT_ID);

        assertThat(manual.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_MATCHED);
        assertThat(result.resetLines()).isZero();
    }

    @Test
    void publishesRelinkedEventWithUploaderAndProject() {
        service.relink(DOC_ID, PROJECT_ID);

        verify(publisher).publishEvent(new ProcurementRelinkedEvent(
                DOC_ID, 3L, null, PROJECT_ID, "Project", "ACME", null));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static ProjectEntity project(Long id, ProjectStatus status) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName("Project");
        p.setStatus(status);
        return p;
    }

    private ProcurementLineEntity line(int no, String rawName, MatchStatus status, Long materialId, MatchTier tier) {
        ProcurementLineEntity l = new ProcurementLineEntity();
        l.setDocument(document);
        l.setLineNo(no);
        l.setRawName(rawName);
        l.setMatchStatus(status);
        l.setMatchedMaterialId(materialId);
        l.setMatchTier(tier);
        return l;
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getMessageCode()).isEqualTo(code);
        });
    }
}
