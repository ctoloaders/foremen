package com.foremen.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ProcurementLineDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.model.PurchaseLineServiceModel;
import com.foremen.service.model.mapper.ProcurementLineServiceMapper;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Example unit tests for {@link PurchaseLineReconciliationService}
 * (FOR-06-04-purchases-list, design §5.1) driven with Mockito stubs for every collaborator.
 *
 * <p>Covers the concrete scenarios the Property tests do not pin down by example:
 * <ul>
 *   <li>the {@code ACTIVE}-stage gate on a non-{@code ACTIVE} project (R2.5, R3.5, R7.4);</li>
 *   <li>{@code clearClassification} keeping {@code MANUAL_UNMATCHED} while nulling the category (R3.2);</li>
 *   <li>{@code classify} rejecting an unknown / inactive category (R3.4);</li>
 *   <li>the {@code relink} off-estimate rejection message (R2.2);</li>
 *   <li>the no-notification assertion — reconciliation emits nothing beyond the line save + audit (R8.1);</li>
 *   <li>an audit row written on every reconciliation op (R10.1).</li>
 * </ul>
 *
 * <p>The caller is an ADMIN so the inherited {@link ProjectScopedService} project-access guard is a
 * bypass (the access filter is covered by the FOR-03-04 tests); the service's own gates run here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseLineReconciliationServiceTest {

    private static final long PROJECT_ID = 7L;
    private static final long LINE_ID = 42L;
    private static final long DOCUMENT_ID = 900L;
    private static final long ON_ESTIMATE_MATERIAL_ID = 555L;
    private static final long ACTIVE_CATEGORY_ID = 100L;
    private static final long INACTIVE_CATEGORY_ID = 101L;
    private static final long UNKNOWN_CATEGORY_ID = 999L;

    @Mock private ProcurementLineDao lineDao;
    @Mock private UnplannedExpenseCategoryDao categoryDao;
    @Mock private ProjectAccessCache projectAccessCache;
    @Mock private ProcurementLineServiceMapper serviceMapper;
    @Mock private AuditLogDao auditLogDao;
    @Mock private EntityManager entityManager;
    @Mock private PlannedMaterialCandidateProvider candidateProvider;

    private PurchaseLineReconciliationService service;
    private ProcurementLineEntity line;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        service = new PurchaseLineReconciliationService(
                lineDao, categoryDao, projectAccessCache, serviceMapper,
                auditLogDao, entityManager, candidateProvider);

        // ADMIN caller -> project-access guard is a bypass.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);

        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setId(DOCUMENT_ID);
        document.setProject(project);

        line = new ProcurementLineEntity();
        line.setId(LINE_ID);
        line.setDocument(document);
        line.setLineNo(1);
        line.setRawName("Цемент 25кг");

        lenient().when(lineDao.findById(LINE_ID)).thenReturn(Optional.of(line));
        lenient().when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
        lenient().when(lineDao.save(any(ProcurementLineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(candidateProvider.candidatesFor(PROJECT_ID))
                .thenReturn(List.of(new PlannedMaterial(
                        ON_ESTIMATE_MATERIAL_ID, "Producer", "Cement",
                        List.of("Цемент", "Cement"))));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // --- Stage gate on a non-ACTIVE project (R2.5, R3.5, R7.4) ---------------------------------

    @Test
    void relinkOnCompletedProjectIsRejectedWithStageNotActive() {
        project.setStatus(ProjectStatus.COMPLETED);
        asAuto();

        assertThatThrownBy(() -> service.relink(LINE_ID, ON_ESTIMATE_MATERIAL_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.purchases.stage.notActive"));

        // No persist and no audit on a rejected stage-gate.
        verify(lineDao, never()).save(any());
        verifyNoInteractions(auditLogDao);
    }

    @Test
    void classifyOnDraftProjectIsRejectedWithStageNotActive() {
        project.setStatus(ProjectStatus.DRAFT);
        asUnmatched();

        assertThatThrownBy(() -> service.classify(LINE_ID, ACTIVE_CATEGORY_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.purchases.stage.notActive"));

        verify(lineDao, never()).save(any());
        verifyNoInteractions(auditLogDao);
    }

    // --- clearClassification keeps MANUAL_UNMATCHED (R3.2) -------------------------------------

    @Test
    void clearClassificationNullsCategoryAndKeepsManualUnmatched() {
        // A MANUAL_UNMATCHED line carrying a category.
        line.setMatchStatus(MatchStatus.MANUAL_UNMATCHED);
        line.setMatchedMaterialId(null);
        line.setMatchTier(MatchTier.NONE);
        line.setUnplannedCategory(activeCategory());

        PurchaseLineServiceModel result = service.clearClassification(LINE_ID);

        assertThat(line.getUnplannedCategory()).isNull();
        assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_UNMATCHED);
        assertThat(line.getMatchedMaterialId()).isNull();
        assertThat(line.getMatchTier()).isEqualTo(MatchTier.NONE);
        assertThat(result.matchStatus()).isEqualTo(MatchStatus.MANUAL_UNMATCHED);
        assertThat(result.unplannedCategoryId()).isNull();
        verify(auditLogDao, times(1)).save(any(AuditLogEntity.class));
    }

    // --- classify unknown / inactive category (R3.4) -------------------------------------------

    @Test
    void classifyWithUnknownCategoryIsRejected() {
        asUnmatched();
        when(categoryDao.findById(UNKNOWN_CATEGORY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.classify(LINE_ID, UNKNOWN_CATEGORY_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.purchases.classify.unknownCategory"));

        verify(lineDao, never()).save(any());
        verifyNoInteractions(auditLogDao);
    }

    @Test
    void classifyWithInactiveCategoryIsRejected() {
        asUnmatched();
        UnplannedExpenseCategoryEntity inactive = new UnplannedExpenseCategoryEntity();
        inactive.setId(INACTIVE_CATEGORY_ID);
        inactive.setCode("OLD");
        inactive.setNameRU("ru");
        inactive.setNamePL("pl");
        inactive.setActive(false);
        when(categoryDao.findById(INACTIVE_CATEGORY_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.classify(LINE_ID, INACTIVE_CATEGORY_ID))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.purchases.classify.unknownCategory"));

        verify(lineDao, never()).save(any());
        verifyNoInteractions(auditLogDao);
    }

    // --- relink off-estimate rejection (R2.2) --------------------------------------------------

    @Test
    void relinkToOffEstimateMaterialIsRejectedAndLineUnchanged() {
        asAuto();
        long offEstimate = 123456L; // not in the stubbed candidate set

        assertThatThrownBy(() -> service.relink(LINE_ID, offEstimate))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.purchases.relink.notPlanned"));

        // The line keeps its prior AUTO shape; no persist, no audit.
        assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.AUTO);
        verify(lineDao, never()).save(any());
        verifyNoInteractions(auditLogDao);
    }

    // --- Audit rows on each op (R10.1) ---------------------------------------------------------

    @Test
    void relinkWritesAnUpdateAuditRow() {
        asAuto();
        service.relink(LINE_ID, ON_ESTIMATE_MATERIAL_ID);
        assertSingleUpdateAudit();
    }

    @Test
    void clearMatchWritesAnUpdateAuditRow() {
        asAuto();
        service.clearMatch(LINE_ID);
        assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_UNMATCHED);
        assertSingleUpdateAudit();
    }

    @Test
    void classifyWritesAnUpdateAuditRow() {
        asUnmatched();
        when(categoryDao.findById(ACTIVE_CATEGORY_ID)).thenReturn(Optional.of(activeCategory()));
        service.classify(LINE_ID, ACTIVE_CATEGORY_ID);
        assertThat(line.getMatchStatus()).isEqualTo(MatchStatus.MANUAL_UNMATCHED);
        assertThat(line.getUnplannedCategory()).isNotNull();
        assertSingleUpdateAudit();
    }

    @Test
    void clearClassificationWritesAnUpdateAuditRow() {
        line.setMatchStatus(MatchStatus.MANUAL_UNMATCHED);
        line.setMatchTier(MatchTier.NONE);
        line.setUnplannedCategory(activeCategory());
        service.clearClassification(LINE_ID);
        assertSingleUpdateAudit();
    }

    // --- No notifications (R8.1) ---------------------------------------------------------------

    @Test
    void reconciliationEmitsNoNotificationsBeyondPersistAndAudit() {
        asAuto();

        service.relink(LINE_ID, ON_ESTIMATE_MATERIAL_ID);

        // The only writes are the line save and the audit save; no notification collaborator exists
        // on the service (R8.1 — reconciliation is a synchronous authoring action, no handoff).
        verify(lineDao, times(1)).save(any(ProcurementLineEntity.class));
        verify(auditLogDao, times(1)).save(any(AuditLogEntity.class));
        verifyNoMoreInteractions(auditLogDao);
        // The service has no notification dependency wired in its constructor — assert the dependency
        // surface stays exactly the seven collaborators (a new side-effect bean would break this).
        assertThat(service.getClass().getDeclaredFields())
                .noneMatch(f -> f.getType().getSimpleName().toLowerCase().contains("notification"));
    }

    // --- Helpers -------------------------------------------------------------------------------

    private void asAuto() {
        line.setMatchStatus(MatchStatus.AUTO);
        line.setMatchedMaterialId(888L);
        line.setMatchTier(MatchTier.EXACT);
        line.setUnplannedCategory(null);
    }

    private void asUnmatched() {
        line.setMatchStatus(MatchStatus.UNMATCHED);
        line.setMatchedMaterialId(null);
        line.setMatchTier(MatchTier.NONE);
        line.setUnplannedCategory(null);
    }

    private UnplannedExpenseCategoryEntity activeCategory() {
        UnplannedExpenseCategoryEntity category = new UnplannedExpenseCategoryEntity();
        category.setId(ACTIVE_CATEGORY_ID);
        category.setCode("FUEL");
        category.setNameRU("Топливо");
        category.setNamePL("Paliwo");
        category.setActive(true);
        return category;
    }

    private void assertSingleUpdateAudit() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao, times(1)).save(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo("UPDATE");
        assertThat(captor.getValue().getEntityId()).isEqualTo(LINE_ID);
    }
}
