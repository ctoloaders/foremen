package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

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
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ocr.OcrService;
import com.foremen.service.procurement.MatchEngine;
import com.foremen.service.procurement.MatchResult;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link DocumentRelinkService} (FOR-07-03 task 5.1).
 *
 * <ul>
 *   <li><b>Invariant:</b> every re-matched line satisfies {@link MatchEngine#satisfiesInvariant} and the
 *       category is never left on a matched line. Validates Requirements 5.2, 5.3, 5.6.</li>
 *   <li><b>Manual preservation:</b> {@code MANUAL_UNMATCHED} is never touched; {@code MANUAL_MATCHED} is
 *       kept unless the document moves from another project and the material is not a candidate of the
 *       new project. Validates Requirements 5.4, 5.5.</li>
 *   <li><b>Idempotent rejection:</b> relinking again to the same project is a 409 that changes nothing
 *       and emits no second event. Validates Requirements 5.2, 12.2.</li>
 * </ul>
 *
 * <p>Pure in-memory: collaborators are mocks, the real {@link MatchEngine} is used.
 */
@Tag("Feature: FOR-07-03-company-procurement, Property: relink")
class DocumentRelinkPropertyTest {

    private static final Long DOC_ID = 10L;
    private static final Long NEW_PROJECT_ID = 5L;
    private static final Long OLD_PROJECT_ID = 6L;
    private static final List<String> RAW_NAMES = List.of(
            "Cement Holcim 25kg", "cement", "Paint white", "Nails", "Płytki ceramiczne", "Cement Holcim", "x");
    private static final List<Long> MATERIAL_IDS = List.of(7L, 8L, 99L);

    private record LineSpec(String rawName, MatchStatus status, Long materialId, boolean hasCategory) {
    }

    private record Scenario(boolean hadProject, boolean documentCategory, List<LineSpec> lines) {
    }

    private record Fixture(DocumentRelinkService service, ProcurementDocumentEntity document,
                           ApplicationEventPublisher publisher, List<ProcurementLineEntity> lines,
                           UnplannedExpenseCategoryEntity category) {
    }

    // --- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<LineSpec> line = Arbitraries.of(RAW_NAMES).flatMap(name ->
                Arbitraries.of(MatchStatus.values()).flatMap(status ->
                        Arbitraries.of(MATERIAL_IDS).flatMap(id ->
                                Arbitraries.of(true, false).map(cat -> {
                                    boolean matched = status == MatchStatus.AUTO
                                            || status == MatchStatus.MANUAL_MATCHED;
                                    Long materialId = matched ? id : null;
                                    // R1.3: a category only ever sits on an unmatched line.
                                    return new LineSpec(name, status, materialId, cat && !matched);
                                }))));
        return Arbitraries.of(true, false).flatMap(hadProject ->
                Arbitraries.of(true, false).flatMap(docCat ->
                        line.list().ofMinSize(0).ofMaxSize(8)
                                .map(lines -> new Scenario(hadProject, docCat, lines))));
    }

    // --- properties ------------------------------------------------------------------------------

    @Property(tries = 200)
    void rematchedLinesSatisfyInvariantAndCategoryNeverOnMatched(@ForAll("scenarios") Scenario s) {
        Fixture f = fixture(s);
        MatchEngine engine = new MatchEngine();
        List<PlannedMaterial> candidates = candidates();

        f.service().relink(DOC_ID, NEW_PROJECT_ID);

        for (int i = 0; i < f.lines().size(); i++) {
            ProcurementLineEntity line = f.lines().get(i);
            LineSpec before = s.lines().get(i);
            boolean rematched = before.status() == MatchStatus.AUTO || before.status() == MatchStatus.UNMATCHED;
            if (rematched) {
                MatchResult now = new MatchResult(line.getMatchedMaterialId(), line.getMatchTier(),
                        line.getMatchStatus());
                assertThat(MatchEngine.satisfiesInvariant(now)).as("invariant for %s", before).isTrue();
                // identical to a fresh engine run against the new project's candidates
                MatchResult expected = engine.match(
                        new com.foremen.service.procurement.ExtractedLine(before.rawName(), null), candidates);
                assertThat(now).isEqualTo(expected);
            }
            boolean matched = line.getMatchStatus() == MatchStatus.AUTO
                    || line.getMatchStatus() == MatchStatus.MANUAL_MATCHED;
            if (matched) {
                assertThat(line.getUnplannedCategory()).as("category on matched line").isNull();
                assertThat(line.getMatchedMaterialId()).isNotNull();
            } else {
                assertThat(line.getMatchedMaterialId()).as("material on unmatched line").isNull();
            }
        }
        assertThat(f.document().getProject().getId()).isEqualTo(NEW_PROJECT_ID);
    }

    @Property(tries = 200)
    void manualMatchesArePreservedUnlessForeignAfterProjectChange(@ForAll("scenarios") Scenario s) {
        Fixture f = fixture(s);
        List<Long> candidateIds = candidates().stream().map(PlannedMaterial::materialId).toList();

        DocumentRelinkService.Result result = f.service().relink(DOC_ID, NEW_PROJECT_ID);

        int expectedReset = 0;
        for (int i = 0; i < f.lines().size(); i++) {
            ProcurementLineEntity line = f.lines().get(i);
            LineSpec before = s.lines().get(i);
            if (before.status() == MatchStatus.MANUAL_UNMATCHED) {
                assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_UNMATCHED);
                assertThat(line.getMatchedMaterialId()).isNull();
            } else if (before.status() == MatchStatus.MANUAL_MATCHED) {
                boolean foreign = s.hadProject() && !candidateIds.contains(before.materialId());
                if (foreign) {
                    expectedReset++;
                    assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
                    assertThat(line.getMatchedMaterialId()).isNull();
                    assertThat(line.getMatchTier()).isEqualTo(MatchTier.NONE);
                } else {
                    assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_MATCHED);
                    assertThat(line.getMatchedMaterialId()).isEqualTo(before.materialId());
                }
            }
        }
        assertThat(result.resetLines()).isEqualTo(expectedReset);
        long matched = f.lines().stream().filter(l -> l.getMatchStatus() == MatchStatus.AUTO
                || l.getMatchStatus() == MatchStatus.MANUAL_MATCHED).count();
        assertThat(result.matchedLines()).isEqualTo((int) matched);
    }

    @Property(tries = 100)
    void secondRelinkToSameProjectIsRejectedAndChangesNothing(@ForAll("scenarios") Scenario s) {
        Fixture f = fixture(s);
        f.service().relink(DOC_ID, NEW_PROJECT_ID);
        List<String> snapshot = snapshot(f.lines());

        assertThatThrownBy(() -> f.service().relink(DOC_ID, NEW_PROJECT_ID))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo("error.companyProcurement.relink.sameProject");
                });

        assertThat(snapshot(f.lines())).isEqualTo(snapshot);
        verify(f.publisher(), times(1)).publishEvent(any(Object.class));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static List<PlannedMaterial> candidates() {
        return List.of(
                new PlannedMaterial(7L, "Holcim", "cement", List.of("Cement Holcim")),
                new PlannedMaterial(8L, "Cersanit", "plytki", List.of("Płytki ceramiczne")));
    }

    private static List<String> snapshot(List<ProcurementLineEntity> lines) {
        List<String> out = new ArrayList<>();
        for (ProcurementLineEntity l : lines) {
            out.add(l.getMatchStatus() + "|" + l.getMatchedMaterialId() + "|" + l.getMatchTier() + "|"
                    + (l.getUnplannedCategory() == null ? "-" : "cat"));
        }
        return out;
    }

    private static Fixture fixture(Scenario s) {
        EntityManager em = mock(EntityManager.class);
        PlannedMaterialCandidateProvider provider = mock(PlannedMaterialCandidateProvider.class);
        ProcurementDocumentService documentService = mock(ProcurementDocumentService.class);
        OcrService ocr = mock(OcrService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DocumentRelinkService service = new DocumentRelinkService(
                em, new MatchEngine(), provider, documentService, ocr, publisher);

        ProjectEntity newProject = project(NEW_PROJECT_ID);
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setId(DOC_ID);
        document.setStatus(ProcessingStatus.PARSED);
        if (s.hadProject()) {
            document.setProject(project(OLD_PROJECT_ID));
        }
        UnplannedExpenseCategoryEntity category = new UnplannedExpenseCategoryEntity();
        category.setId(2L);
        if (s.documentCategory()) {
            document.setExpenseCategory(category);
        }
        List<ProcurementLineEntity> lines = new ArrayList<>();
        int no = 1;
        for (LineSpec spec : s.lines()) {
            ProcurementLineEntity l = new ProcurementLineEntity();
            l.setDocument(document);
            l.setLineNo(no++);
            l.setRawName(spec.rawName());
            l.setMatchStatus(spec.status());
            l.setMatchedMaterialId(spec.materialId());
            l.setMatchTier(spec.materialId() == null ? MatchTier.NONE : MatchTier.EXACT);
            if (spec.hasCategory()) {
                l.setUnplannedCategory(category);
            }
            lines.add(l);
        }
        document.getLines().addAll(lines);

        when(em.find(ProcurementDocumentEntity.class, DOC_ID)).thenReturn(document);
        when(em.find(ProjectEntity.class, NEW_PROJECT_ID)).thenReturn(newProject);
        when(provider.candidatesFor(NEW_PROJECT_ID)).thenReturn(candidates());
        when(documentService.auditSnapshot(any())).thenReturn("{}");
        return new Fixture(service, document, publisher, lines, category);
    }

    private static ProjectEntity project(Long id) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName("Project");
        p.setStatus(ProjectStatus.ACTIVE);
        return p;
    }
}
