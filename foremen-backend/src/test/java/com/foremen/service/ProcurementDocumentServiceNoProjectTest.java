package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.config.image.ImageStorageProperties;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.ocr.OcrResult;
import com.foremen.service.ocr.OcrService;
import com.foremen.service.ocr.ParsedLine;
import com.foremen.service.procurement.ExtractedLine;
import com.foremen.service.procurement.MatchEngine;
import com.foremen.service.procurement.MatchResult;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;
import com.foremen.service.procurement.ProcurementOcrRequested;
import com.foremen.service.procurement.ReprocessLinePartitioner;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-03 task 5.2 - {@link ProcurementDocumentService} with a {@code null} project (company
 * expense): {@code procurement/company/} namespace, no stage gate, all lines {@code UNMATCHED}/
 * {@code NONE} (no candidates), the document category applied to unmatched lines only, and
 * re-process without a project. Collaborators are mocked.
 *
 * <p>Validates: Requirements 2.1, 2.3, 2.4, 3.3
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcurementDocumentServiceNoProjectTest {

    private static final long DOCUMENT_ID = 901L;
    private static final long CATEGORY_ID = 12L;

    @Mock private ProcurementDocumentDao documentDao;
    @Mock private ProjectAccessCache projectAccessCache;
    @Mock private ProcurementDocumentServiceMapper serviceMapper;
    @Mock private AuditLogDao auditLogDao;
    @Mock private EntityManager entityManager;
    @Mock private ImageStorage imageStorage;
    @Mock private OcrService ocrService;
    @Mock private MatchEngine matchEngine;
    @Mock private PlannedMaterialCandidateProvider candidateProvider;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ProcurementDocumentService service;
    private UnplannedExpenseCategoryEntity category;
    private ProcurementDocumentEntity saved;

    @BeforeEach
    void setUp() {
        service = new ProcurementDocumentService(
                documentDao, projectAccessCache, serviceMapper, auditLogDao, entityManager,
                imageStorage, ocrService, matchEngine, candidateProvider, new ReprocessLinePartitioner(),
                eventPublisher, new ImageStorageProperties(null, null, null, null, null, null, null));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        category = new UnplannedExpenseCategoryEntity();
        category.setId(CATEGORY_ID);
        category.setActive(true);
        when(entityManager.find(UnplannedExpenseCategoryEntity.class, CATEGORY_ID)).thenReturn(category);

        when(documentDao.save(any(ProcurementDocumentEntity.class))).thenAnswer(inv -> {
            ProcurementDocumentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(DOCUMENT_ID);
            }
            saved = e;
            return e;
        });
        when(imageStorage.isConfigured()).thenReturn(true);
        when(imageStorage.storeObject(any(), anyString())).thenReturn("procurement/company/raw.png");
        when(imageStorage.readObject(any())).thenReturn(new byte[] {1});
        when(ocrService.isEnabled()).thenReturn(true);
        when(serviceMapper.toServiceExtendedModel(any())).thenAnswer(inv -> {
            ProcurementDocumentEntity e = inv.getArgument(0);
            ProcurementDocumentServiceExtendedModel m = new ProcurementDocumentServiceExtendedModel();
            m.setId(e.getId());
            m.setLines(null);
            return m;
        });
        when(matchEngine.match(any(ExtractedLine.class), any()))
                .thenReturn(new MatchResult(null, MatchTier.NONE, MatchStatus.UNMATCHED));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "r.png", "image/png", new byte[] {1, 2, 3});
    }

    private static ParsedLine line(String name) {
        return new ParsedLine(name, BigDecimal.ONE, "szt", BigDecimal.TEN, BigDecimal.TEN, null);
    }

    private static ProcurementDocumentEntity noProjectDocument(ProcessingStatus status) {
        ProcurementDocumentEntity d = new ProcurementDocumentEntity();
        d.setId(DOCUMENT_ID);
        d.setProject(null);
        d.setStatus(status);
        d.setRawObjectKey("procurement/company/raw.png");
        d.setSourceMimeType("image/png");
        d.setUploadedAt(OffsetDateTime.now());
        return d;
    }

    @Test
    @DisplayName("upload without a project stores under procurement/company/, no stage gate, project stays null (R2.1, R2.3)")
    void uploadNoProjectUsesCompanyNamespaceWithoutStageGate() {
        ProcurementDocumentServiceExtendedModel result = service.upload(null, png(), null, null);

        assertThat(result.getId()).isEqualTo(DOCUMENT_ID);
        verify(imageStorage).storeObject(any(), eq("procurement/company/"));
        // No project lookup at all: neither the stage gate (find) nor a project reference.
        verify(entityManager, never()).find(eq(ProjectEntity.class), any());
        verify(entityManager, never()).getReference(eq(ProjectEntity.class), any());
        assertThat(saved.getProject()).isNull();
        assertThat(saved.getStatus()).isEqualTo(ProcessingStatus.QUEUED);
        verify(eventPublisher).publishEvent(any(ProcurementOcrRequested.class));
    }

    @Test
    @DisplayName("upload with a project still applies the stage gate and the project namespace (regression)")
    void uploadWithProjectStillGated() {
        ProjectEntity draft = new ProjectEntity();
        draft.setId(7L);
        draft.setStatus(ProjectStatus.DRAFT);
        when(entityManager.find(ProjectEntity.class, 7L)).thenReturn(draft);

        assertThatThrownBy(() -> service.upload(7L, png(), null, null))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(ProcurementDocumentService.STAGE_NOT_ACTIVE);
        verify(imageStorage, never()).storeObject(any(), anyString());
    }

    @Test
    @DisplayName("an unknown / inactive expense category is rejected before anything is stored (R3.2)")
    void unknownCategoryRejected() {
        assertThatThrownBy(() -> service.upload(null, png(), 999L, null))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(ProcurementDocumentService.EXPENSE_CATEGORY_UNKNOWN);

        category.setActive(false);
        assertThatThrownBy(() -> service.upload(null, png(), CATEGORY_ID, null))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(ProcurementDocumentService.EXPENSE_CATEGORY_UNKNOWN);

        verify(imageStorage, never()).storeObject(any(), anyString());
        verify(documentDao, never()).save(any());
    }

    @Test
    @DisplayName("processing a no-project document: company processed namespace, no candidates, all lines UNMATCHED/NONE (R2.4)")
    void processNoProjectAllLinesUnmatched() {
        ProcurementDocumentEntity document = noProjectDocument(ProcessingStatus.QUEUED);
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(ocrService.process(any(), any(), anyString(), any())).thenReturn(new OcrResult(List.of(),
                List.of(line("Klej"), line("Cement")), "Castorama", BigDecimal.TEN, null, null, null));

        ProcessingStatus terminal = service.processQueued(DOCUMENT_ID, stage -> { });

        assertThat(terminal).isEqualTo(ProcessingStatus.PARSED);
        verify(ocrService).process(any(), any(), eq("procurement/company/processed/"), any());
        verify(candidateProvider, never()).candidatesFor(anyLong());
        assertThat(document.getLines()).hasSize(2).allSatisfy(l -> {
            assertThat(l.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
            assertThat(l.getMatchTier()).isEqualTo(MatchTier.NONE);
            assertThat(l.getMatchedMaterialId()).isNull();
            assertThat(l.getUnplannedCategory()).isNull();
        });
    }

    @Test
    @DisplayName("the document category is applied to unmatched lines only, never to matched ones (R3.3)")
    void categoryAppliedToUnmatchedLinesOnly() {
        ProcurementDocumentEntity document = noProjectDocument(ProcessingStatus.QUEUED);
        document.setExpenseCategory(category);
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(matchEngine.match(any(ExtractedLine.class), any())).thenAnswer(inv -> {
            ExtractedLine extracted = inv.getArgument(0);
            return "Cement".equals(extracted.rawName())
                    ? new MatchResult(5L, MatchTier.EXACT, MatchStatus.AUTO)
                    : new MatchResult(null, MatchTier.NONE, MatchStatus.UNMATCHED);
        });
        when(ocrService.process(any(), any(), anyString(), any())).thenReturn(new OcrResult(List.of(),
                List.of(line("Klej"), line("Cement")), "Castorama", BigDecimal.TEN, null, null, null));

        service.processQueued(DOCUMENT_ID, stage -> { });

        List<ProcurementLineEntity> lines = document.getLines();
        assertThat(lines).hasSize(2);
        ProcurementLineEntity unmatched = lines.stream()
                .filter(l -> l.getMatchStatus() == MatchStatus.UNMATCHED).findFirst().orElseThrow();
        ProcurementLineEntity matched = lines.stream()
                .filter(l -> l.getMatchStatus() == MatchStatus.AUTO).findFirst().orElseThrow();
        assertThat(unmatched.getUnplannedCategory()).isSameAs(category);
        assertThat(matched.getUnplannedCategory()).isNull();
    }

    @Test
    @DisplayName("re-process of a no-project document skips the stage gate and re-enqueues it (R2.4)")
    void reprocessNoProject() {
        ProcurementDocumentEntity document = noProjectDocument(ProcessingStatus.FAILED);
        document.setFailureReason("procurement.ocr.failed");
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

        service.reprocessUnchecked(DOCUMENT_ID);

        assertThat(document.getStatus()).isEqualTo(ProcessingStatus.QUEUED);
        assertThat(document.getFailureReason()).isNull();
        verify(entityManager, never()).find(eq(ProjectEntity.class), any());
        verify(eventPublisher).publishEvent(new ProcurementOcrRequested(DOCUMENT_ID));
    }

    @Test
    @DisplayName("re-process of a no-project document in a non-terminal status is refused (409)")
    void reprocessNoProjectWrongStatus() {
        ProcurementDocumentEntity document = noProjectDocument(ProcessingStatus.QUEUED);
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.reprocessUnchecked(DOCUMENT_ID))
                .isInstanceOf(ForemenApiException.class);
        verify(eventPublisher, never()).publishEvent(any(ProcurementOcrRequested.class));
    }
}
