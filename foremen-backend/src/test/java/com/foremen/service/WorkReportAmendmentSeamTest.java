package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.WorkReportRoomModel;
import com.foremen.dao.AmendmentLineDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportInputMode;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.mapper.WorkReportServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-08 task 7.2 (Requirement 8.2, 8.3; design §Effective_Date_Seam) — the seam contract test for
 * {@code WorkReportService.resolveClientUnitPrice}. The seam resolves the frozen revenue unit price as
 * {@code line.unitPrice + Σ AmendmentLine.extraUnitPrice} over the {@code EFFECTIVE}, already-in-force
 * amendment lines for the line's {@code workItem} (R8.1) — the sum supplied by
 * {@link AmendmentLineDao#sumExtraUnitPriceForEffectiveWorkItem(Long, java.time.LocalDate)}.
 *
 * <p>{@code resolveClientUnitPrice} is private and is reached only through {@code create(...)}, which
 * freezes {@code clientUnitPrice} (and {@code revenueValue = round2(clientUnitPrice × totalQuantity)})
 * onto the persisted report. So both cases are asserted through that public path on the committed
 * entity — exactly how production code exercises the seam.
 *
 * <ul>
 *   <li><b>Case 1 — no {@code EFFECTIVE} amendment (R8.2).</b> With the DAO sum stubbed to zero (the
 *       state of this build, where no amendment can reach {@code EFFECTIVE} until FOR-06-09), the
 *       resolved client unit price equals the line's frozen {@code unitPrice} — behaviour unchanged.</li>
 *   <li><b>Case 2 — a test-only {@code EFFECTIVE} fixture (R8.3).</b> With the DAO sum stubbed to a
 *       positive delta, the resolved client unit price equals {@code unitPrice + delta} — documenting
 *       the contract FOR-06-09 switches on once an amendment becomes effective.</li>
 * </ul>
 *
 * <p>Mirrors {@code WorkReportServiceTest}'s plain-Mockito setup (DAOs / mapper / event publisher /
 * {@link EntityManager} mocked; caller identity driven through {@link SecurityContextHolder}).
 *
 * Feature: FOR-06-08-amendments, Effective_Date_Seam
 * Validates: Requirements 8.2, 8.3
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkReportAmendmentSeamTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORK_ITEM_ID = 7L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long ROOM_A = 100L;
    private static final Long WORKER_ID = 500L;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("12.50");
    // The report's performed-to date is the applicable report date the seam filters amendments by.
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 1, 2);

    @Mock
    private WorkReportDao workReportDao;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private EstimateLineDao estimateLineDao;
    @Mock
    private AmendmentLineDao amendmentLineDao;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private WorkReportServiceMapper serviceMapper;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private WorkReportService service;

    @BeforeEach
    void setUp() {
        service = new WorkReportService(
                workReportDao, projectMemberDao, estimateLineDao, amendmentLineDao, projectAccessCache,
                serviceMapper, auditLogDao, entityManager, eventPublisher);
        lenient().when(workReportDao.save(any(WorkReportEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entityManager.getReference(any(), any()))
                .thenAnswer(inv -> referenceStub(inv.getArgument(0), (Long) inv.getArgument(1)));
        lenient().when(serviceMapper.toServiceExtendedModel(any()))
                .thenReturn(new WorkReportServiceExtendedModel());
        authenticate(WORKER_ID, "ROLE_WORKER");
        stubWorkerMember();
        stubEstimateLine(planFor(ROOM_A, "10"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Case 1 — with no EFFECTIVE amendment the seam delta is zero, so clientUnitPrice = line.unitPrice (R8.2)")
    void noEffectiveAmendmentLeavesClientUnitPriceUnchanged() {
        // The state of this build: no amendment can reach EFFECTIVE (owned by FOR-06-09), so the DAO
        // sum is zero. Behaviour is unchanged from pre-FOR-06-08 work-reporting.
        when(amendmentLineDao.sumExtraUnitPriceForEffectiveWorkItem(any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service.create(createModel(row(ROOM_A, "4.000", WorkReportInputMode.ABSOLUTE)));

        WorkReportEntity saved = capturedSave();
        // The resolved client unit price is the line's frozen unitPrice, untouched by the seam.
        assertThat(saved.getClientUnitPrice()).isEqualByComparingTo(UNIT_PRICE);
        // revenueValue = round2(12.50 × 4.000) = 50.00 — no amendment contribution.
        assertThat(saved.getRevenueValue()).isEqualByComparingTo("50.00");
        // The seam queried the sum for this line's work item at the report date (R8.1).
        verify(amendmentLineDao).sumExtraUnitPriceForEffectiveWorkItem(eq(WORK_ITEM_ID), eq(REPORT_DATE));
    }

    @Test
    @DisplayName("Case 2 — a test-only EFFECTIVE fixture adds its extraUnitPrice sum: clientUnitPrice = unitPrice + delta (R8.3)")
    void effectiveAmendmentDeltaIsAddedToClientUnitPrice() {
        // A test-only EFFECTIVE fixture: the DAO reports a positive extra-unit-price sum for this work
        // item. This is the contract FOR-06-09 switches on once an amendment becomes effective.
        BigDecimal delta = new BigDecimal("3.50");
        when(amendmentLineDao.sumExtraUnitPriceForEffectiveWorkItem(eq(WORK_ITEM_ID), eq(REPORT_DATE)))
                .thenReturn(delta);

        service.create(createModel(row(ROOM_A, "4.000", WorkReportInputMode.ABSOLUTE)));

        WorkReportEntity saved = capturedSave();
        // The resolved client unit price is unitPrice + Σ extraUnitPrice = 12.50 + 3.50 = 16.00.
        assertThat(saved.getClientUnitPrice()).isEqualByComparingTo("16.00");
        // revenueValue = round2(16.00 × 4.000) = 64.00 — the delta flows through to frozen revenue.
        assertThat(saved.getRevenueValue()).isEqualByComparingTo("64.00");
    }

    // ========================================================================
    // test-data builders / stubs (mirrors WorkReportServiceTest)
    // ========================================================================

    private WorkReportServiceExtendedModel createModel(WorkReportRoomModel... rows) {
        WorkReportServiceExtendedModel model = new WorkReportServiceExtendedModel();
        model.setProjectId(PROJECT_ID);
        model.setWorkItemId(WORK_ITEM_ID);
        model.setWorkerId(WORKER_ID);
        model.setPerformedFrom(LocalDate.of(2026, 1, 1));
        model.setPerformedTo(REPORT_DATE);
        model.setRooms(new ArrayList<>(List.of(rows)));
        return model;
    }

    private static WorkReportRoomModel row(Long roomId, String quantity, WorkReportInputMode mode) {
        return new WorkReportRoomModel(null, roomId, null, new BigDecimal(quantity), null, mode);
    }

    private EstimateLineRoomQtyEntity planFor(Long roomId, String quantity) {
        EstimateLineRoomQtyEntity rq = new EstimateLineRoomQtyEntity();
        rq.setRoom(referenceStub(RoomEntity.class, roomId));
        rq.setQuantity(new BigDecimal(quantity));
        return rq;
    }

    private void stubEstimateLine(EstimateLineRoomQtyEntity... plans) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setWorkItem(workItem(WORK_ITEM_ID, CATEGORY_ID));
        line.setUnitPrice(UNIT_PRICE);
        line.setRoomQtys(new ArrayList<>(List.of(plans)));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of(line));
    }

    private static WorkItemEntity workItem(Long id, Long categoryId) {
        WorkItemEntity wi = new WorkItemEntity();
        wi.setId(id);
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(categoryId);
        wi.setWorkCategory(c);
        return wi;
    }

    private void stubWorkerMember() {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(referenceStub(UserEntity.class, WORKER_ID));
        member.setProjectId(PROJECT_ID);
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        RoleEntity role = new RoleEntity();
        role.setCode("WORKER");
        member.setProjectRole(role);
        WorkCategoryEntity cat = new WorkCategoryEntity();
        cat.setId(CATEGORY_ID);
        member.setWorkCategories(new ArrayList<>(List.of(cat)));
        when(projectMemberDao.findByUserIdAndProjectId(WORKER_ID, PROJECT_ID))
                .thenReturn(Optional.of(member));
    }

    private WorkReportEntity capturedSave() {
        ArgumentCaptor<WorkReportEntity> captor = ArgumentCaptor.forClass(WorkReportEntity.class);
        verify(workReportDao).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static <T> T referenceStub(Class<T> type, Long id) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();
            type.getMethod("setId", Long.class).invoke(instance, id);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build reference stub for " + type, e);
        }
    }

    private static void authenticate(Long userId, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
