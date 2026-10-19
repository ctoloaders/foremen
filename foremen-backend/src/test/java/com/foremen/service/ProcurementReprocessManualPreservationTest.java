package com.foremen.service;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.ocr.OcrResult;
import com.foremen.service.ocr.OcrService;
import com.foremen.service.ocr.ParsedLine;
import com.foremen.service.procurement.ExtractedLine;
import com.foremen.service.procurement.MatchEngine;
import com.foremen.service.procurement.MatchResult;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;
import com.foremen.service.procurement.ReprocessLinePartitioner;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * FOR-06-04 task 8.2 — end-to-end example test that the extended FOR-06-03
 * {@link ProcurementDocumentService#reprocess(Long)} path <strong>preserves a manually reconciled
 * line</strong> while replacing the automatic ones (Requirement 5.1, completes FOR-06-03 R8.4).
 *
 * <p>The OCR provider is <strong>stubbed</strong> ({@link OcrService} returns a fixed
 * {@link OcrResult} of one freshly extracted line), so no external service is contacted and the
 * re-process is exercised end-to-end over the real {@link ReprocessLinePartitioner} (the pure
 * manual-preservation rule under test, design §5.2) with the remaining collaborators mocked. This
 * complements the pure partitioner property test (task 3.1a) by proving the wiring in
 * {@code process(...)} actually keeps the preserved lines and renumbers correctly.
 *
 * <p><b>Scenario.</b> A {@code PARSED} document on an {@code ACTIVE} project has two prior lines:
 * <ul>
 *   <li>line 1 — {@code MANUAL_MATCHED} to material {@code 555} (a user's re-link, must be
 *       <em>preserved</em> with its {@code matchedMaterialId} / tier intact);</li>
 *   <li>line 2 — {@code AUTO} (the engine's proposal, must be <em>replaced</em>).</li>
 * </ul>
 * The stubbed OCR re-extracts one line; the {@link MatchEngine} returns {@code UNMATCHED} for it.
 * After {@code reprocess}: the AUTO line is gone, the manual line survives unchanged, the fresh line
 * is appended, and the document's lines are renumbered contiguously with the preserved manual line
 * first (so the {@code (document_id, line_no)} unique constraint holds).
 *
 * <p>The caller is an ADMIN so the inherited {@link ProjectScopedService} project-access guard is a
 * bypass; the service's own stage gate + the partition wiring are what this test exercises.
 *
 * <p>Validates: Requirements 5.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcurementDocumentService.reprocess preserves a manual line and replaces auto (R5.1)")
class ProcurementReprocessManualPreservationTest {

    private static final long PROJECT_ID = 7L;
    private static final long DOCUMENT_ID = 900L;
    private static final long PRESERVED_MATERIAL_ID = 555L;

    @Mock private ProcurementDocumentDao documentDao;
    @Mock private ProjectAccessCache projectAccessCache;
    @Mock private ProcurementDocumentServiceMapper serviceMapper;
    @Mock private AuditLogDao auditLogDao;
    @Mock private EntityManager entityManager;
    @Mock private com.foremen.service.image.ImageStorage imageStorage;
    @Mock private OcrService ocrService;
    @Mock private MatchEngine matchEngine;
    @Mock private PlannedMaterialCandidateProvider candidateProvider;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private ProcurementDocumentService service;
    private ProcurementDocumentEntity document;
    private ProcurementLineEntity manualLine;
    private ProcurementLineEntity autoLine;

    @BeforeEach
    void setUp() {
        // The real pure partitioner is the unit under test inside process(...).
        ReprocessLinePartitioner partitioner = new ReprocessLinePartitioner();
        service = new ProcurementDocumentService(
                documentDao, projectAccessCache, serviceMapper, auditLogDao, entityManager,
                imageStorage, ocrService, matchEngine, candidateProvider, partitioner,
                eventPublisher,
                new ImageStorageProperties(null, null, null, null, null, null, null));

        // ADMIN caller -> the inherited project-access guard is a bypass.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);

        document = new ProcurementDocumentEntity();
        document.setId(DOCUMENT_ID);
        document.setProject(project);
        document.setStatus(ProcessingStatus.PARSED);
        document.setRawObjectKey("procurement/7/raw.png");
        document.setSourceMimeType("image/png");

        // Prior line 1 — a user's manual re-link (MANUAL_MATCHED). Must be preserved.
        manualLine = new ProcurementLineEntity();
        manualLine.setId(11L);
        manualLine.setDocument(document);
        manualLine.setLineNo(1);
        manualLine.setRawName("Цемент 25кг (ручной матч)");
        manualLine.setMatchedMaterialId(PRESERVED_MATERIAL_ID);
        manualLine.setMatchTier(MatchTier.EXACT);
        manualLine.setMatchStatus(MatchStatus.MANUAL_MATCHED);

        // Prior line 2 — an automatic proposal (AUTO). Must be replaced.
        autoLine = new ProcurementLineEntity();
        autoLine.setId(12L);
        autoLine.setDocument(document);
        autoLine.setLineNo(2);
        autoLine.setRawName("Шуруп 4x40 (авто)");
        autoLine.setMatchedMaterialId(888L);
        autoLine.setMatchTier(MatchTier.CATEGORY);
        autoLine.setMatchStatus(MatchStatus.AUTO);

        document.getLines().add(manualLine);
        document.getLines().add(autoLine);

        lenient().when(documentDao.findById(DOCUMENT_ID)).thenReturn(java.util.Optional.of(document));
        lenient().when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
        lenient().when(imageStorage.readObject(document.getRawObjectKey()))
                .thenReturn(new byte[] {1, 2, 3});
        lenient().when(documentDao.save(any(ProcurementDocumentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Stubbed OCR: one freshly extracted line (no pages to store -> processed key stays null).
        ParsedLine fresh = new ParsedLine(
                "Грунтовка 5л (свежая)", new BigDecimal("1"), "шт",
                new BigDecimal("45.00"), new BigDecimal("45.00"), null);
        lenient().when(ocrService.process(any(), any(), any(), any()))
                .thenReturn(new OcrResult(List.of(), List.of(fresh), "ООО Поставщик", new BigDecimal("45.00")));

        // The engine returns UNMATCHED for the fresh line (no candidate match).
        lenient().when(candidateProvider.candidatesFor(PROJECT_ID)).thenReturn(List.of());
        lenient().when(matchEngine.match(any(ExtractedLine.class), any()))
                .thenReturn(new MatchResult(null, MatchTier.NONE, MatchStatus.UNMATCHED));

        // findReadModel's mapper call: a bare model whose getLines() is null is enough (the read-field
        // resolution short-circuits on an empty line list).
        lenient().when(serviceMapper.toServiceExtendedModel(any()))
                .thenReturn(new ProcurementDocumentServiceExtendedModel());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("re-process keeps the MANUAL_MATCHED line (material + tier intact), drops the AUTO "
            + "line, appends the fresh line, and renumbers preserved-first (R5.1)")
    void reprocessPreservesManualLineAndReplacesAuto() {
        // Amendment A1: the preservation/match wiring now runs inside the async processQueued(...)
        // entry point (the worker drives it); the preserved manual line + renumber behaviour is
        // unchanged. Drive it directly with a no-op stage sink.
        service.processQueued(DOCUMENT_ID, stage -> { });

        // The preserved manual line is still attached, byte-for-byte on its reconciliation fields.
        assertThat(document.getLines())
                .as("the MANUAL_MATCHED line must survive the re-process")
                .contains(manualLine);
        assertThat(manualLine.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_MATCHED);
        assertThat(manualLine.getMatchedMaterialId()).isEqualTo(PRESERVED_MATERIAL_ID);
        assertThat(manualLine.getMatchTier()).isEqualTo(MatchTier.EXACT);

        // The AUTO line is dropped (replaced).
        assertThat(document.getLines())
                .as("the AUTO line must be replaced (removed) on re-process")
                .doesNotContain(autoLine);

        // The fresh line was appended with the engine's proposal (UNMATCHED here).
        assertThat(document.getLines())
                .as("exactly one preserved manual line + one fresh line remain")
                .hasSize(2);
        ProcurementLineEntity freshLine = document.getLines().stream()
                .filter(l -> l != manualLine)
                .findFirst()
                .orElseThrow();
        assertThat(freshLine.getRawName()).isEqualTo("Грунтовка 5л (свежая)");
        assertThat(freshLine.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
        assertThat(freshLine.getMatchedMaterialId()).isNull();
        assertThat(freshLine.getMatchTier()).isEqualTo(MatchTier.NONE);

        // Renumbering: preserved manual line first (1), then the fresh line (2) — contiguous and
        // collision-free under the (document_id, line_no) unique constraint.
        assertThat(manualLine.getLineNo()).as("preserved manual line is renumbered first").isEqualTo(1);
        assertThat(freshLine.getLineNo()).as("fresh line follows the preserved line").isEqualTo(2);

        // The document reaches PARSED after a successful re-process.
        assertThat(document.getStatus()).isEqualTo(ProcessingStatus.PARSED);
    }

    @Test
    @DisplayName("re-process with NO manual lines replaces all lines — FOR-06-03-equivalent (R5.2)")
    void reprocessWithoutManualLinesReplacesAll() {
        // Make both prior lines automatic: nothing to preserve -> replace-all (FOR-06-03 behaviour).
        manualLine.setMatchStatus(MatchStatus.AUTO);

        service.processQueued(DOCUMENT_ID, stage -> { });

        assertThat(document.getLines())
                .as("no manual line -> every prior line is replaced by the fresh set")
                .doesNotContain(manualLine, autoLine)
                .hasSize(1);
        ProcurementLineEntity freshLine = document.getLines().get(0);
        assertThat(freshLine.getRawName()).isEqualTo("Грунтовка 5л (свежая)");
        assertThat(freshLine.getLineNo()).isEqualTo(1);
        assertThat(document.getStatus()).isEqualTo(ProcessingStatus.PARSED);
    }
}
