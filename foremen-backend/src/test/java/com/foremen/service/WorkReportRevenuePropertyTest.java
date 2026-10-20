package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;
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
import com.foremen.dao.model.ProjectEntity;
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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * FOR-06-01 task 7.2 — property-based tests for the {@link WorkReportService} revenue derivation
 * (design §7, Properties P1 + P2). The derivation ({@code totalQuantity = Σ rooms.quantity};
 * {@code revenueValue = round2(clientUnitPrice × totalQuantity)}; PERCENT → {@code plan × percent /
 * 100}) runs inside {@code create(...)}, so the properties feed generated rooms through the public
 * create path with the DAOs / mapper / entity-manager mocked (mirroring {@code WorkReportServiceTest})
 * and assert the committed entity against an independently computed reference.
 *
 * <p><b>Property P1 (totals).</b> For any set of ABSOLUTE rows, the persisted {@code totalQuantity}
 * equals the sum of the row quantities and {@code revenueValue} equals {@code round2(unitPrice ×
 * total)} — always derived, never the hand-entered inputs.
 *
 * <p><b>Property P2 (percent resolution).</b> For any PERCENT row with plan {@code p} and percent
 * {@code x ∈ (0,100]}, the resolved absolute quantity equals {@code p × x / 100} (scale 3, HALF_UP).
 *
 * Feature: FOR-06-01-work-reporting, Properties P1, P2
 * Validates: Requirements 1.4, 1.5, 2.2
 */
@Tag("Feature: FOR-06-01-work-reporting, Properties P1 P2")
class WorkReportRevenuePropertyTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORK_ITEM_ID = 7L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long WORKER_ID = 500L;

    private final WorkReportDao workReportDao = mock(WorkReportDao.class);
    private final ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
    private final EstimateLineDao estimateLineDao = mock(EstimateLineDao.class);
    private final AmendmentLineDao amendmentLineDao = mock(AmendmentLineDao.class);
    private final ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
    private final WorkReportServiceMapper serviceMapper = mock(WorkReportServiceMapper.class);
    private final AuditLogDao auditLogDao = mock(AuditLogDao.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final WorkReportService service = new WorkReportService(
            workReportDao, projectMemberDao, estimateLineDao, amendmentLineDao, projectAccessCache,
            serviceMapper, auditLogDao, entityManager, eventPublisher);

    {
        when(workReportDao.save(any(WorkReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // FOR-06-08 seam: no EFFECTIVE amendment exists in this build, so the extra-unit-price sum is
        // zero and the resolved client unit price stays the line's frozen unitPrice (R8.2).
        when(amendmentLineDao.sumExtraUnitPriceForEffectiveWorkItem(any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(entityManager.getReference(any(), any()))
                .thenAnswer(inv -> referenceStub(inv.getArgument(0), (Long) inv.getArgument(1)));
        when(serviceMapper.toServiceExtendedModel(any()))
                .thenReturn(new WorkReportServiceExtendedModel());
        stubWorkerMember();
    }

    /** jqwik clears the context after each try, so re-authenticate the WORKER caller before each. */
    @BeforeTry
    void authenticatePerTry() {
        authenticateWorker();
    }

    @AfterTry
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- generators --------------------------------------------------------

    /** A quantity in (0, 1000], scale 3 — a valid strictly-positive ABSOLUTE amount. */
    @Provide
    Arbitrary<BigDecimal> absoluteQuantities() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.001"), new BigDecimal("1000"))
                .ofScale(3);
    }

    /** A list of 1..6 ABSOLUTE rows, each on a distinct room (indices 1..6 → room ids 101..106). */
    @Provide
    Arbitrary<List<BigDecimal>> absoluteRowSets() {
        return absoluteQuantities().list().ofMinSize(1).ofMaxSize(6);
    }

    /**
     * A percent in [1, 100], scale 2. The lower bound of 1 (combined with a plan ≥ 1) guarantees the
     * resolved {@code plan × percent / 100} is ≥ 0.01 — strictly positive — so the row is valid and
     * the test exercises the resolution math, not the service's reject-non-positive guard (R2.5).
     */
    @Provide
    Arbitrary<BigDecimal> percents() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("1"), new BigDecimal("100"))
                .ofScale(2);
    }

    /** A plan quantity in [1, 1000], scale 3 (≥ 1 keeps the resolved PERCENT quantity positive). */
    @Provide
    Arbitrary<BigDecimal> plans() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("1"), new BigDecimal("1000"))
                .ofScale(3);
    }

    /** A unit price in [0, 100000], scale 2. */
    @Provide
    Arbitrary<BigDecimal> unitPrices() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000"))
                .ofScale(2);
    }

    // ---- Property P1 -------------------------------------------------------

    // Feature: FOR-06-01-work-reporting, Property P1
    // totalQuantity = Σ rows.quantity and revenueValue = round2(unitPrice × total) for ABSOLUTE rows.
    // Validates: Requirements 1.4, 1.5
    @Property(tries = 100)
    void totalAndRevenueAreDerived(
            @ForAll("absoluteRowSets") List<BigDecimal> quantities,
            @ForAll("unitPrices") BigDecimal unitPrice) {
        List<EstimateLineRoomQtyEntity> plans = new ArrayList<>();
        List<WorkReportRoomModel> rows = new ArrayList<>();
        BigDecimal expectedTotal = BigDecimal.ZERO;
        for (int i = 0; i < quantities.size(); i++) {
            long roomId = 101L + i;
            BigDecimal q = quantities.get(i);
            // Each room carries an (unused by ABSOLUTE) plan so the room is on the estimate.
            plans.add(plan(roomId, new BigDecimal("1")));
            rows.add(row(roomId, q, WorkReportInputMode.ABSOLUTE));
            expectedTotal = expectedTotal.add(q);
        }
        stubEstimateLine(unitPrice, plans);

        service.create(model(rows));

        WorkReportEntity saved = capturedSave();
        assertThat(saved.getTotalQuantity()).isEqualByComparingTo(expectedTotal);
        BigDecimal expectedRevenue = unitPrice.multiply(expectedTotal).setScale(2, RoundingMode.HALF_UP);
        assertThat(saved.getRevenueValue()).isEqualByComparingTo(expectedRevenue);
    }

    // ---- Property P2 -------------------------------------------------------

    // Feature: FOR-06-01-work-reporting, Property P2
    // A PERCENT row with plan p and percent x resolves to quantity = round3(p × x / 100).
    // Validates: Requirements 2.2
    @Property(tries = 100)
    void percentRowResolvesToPlanTimesPercent(
            @ForAll("plans") BigDecimal plan,
            @ForAll("percents") BigDecimal percent) {
        long roomId = 101L;
        stubEstimateLine(new BigDecimal("10.00"), List.of(plan(roomId, plan)));

        service.create(model(List.of(row(roomId, percent, WorkReportInputMode.PERCENT))));

        WorkReportEntity saved = capturedSave();
        BigDecimal expected = plan.multiply(percent)
                .divide(new BigDecimal("100"), 3, RoundingMode.HALF_UP);
        assertThat(saved.getRooms()).singleElement().satisfies(r -> {
            assertThat(r.getQuantity()).isEqualByComparingTo(expected);
            // The Plan_Quantity is snapshotted for provenance (R2.6).
            assertThat(r.getPlanQuantity()).isEqualByComparingTo(plan);
        });
        assertThat(saved.getTotalQuantity()).isEqualByComparingTo(expected);
    }

    // ---- helpers -----------------------------------------------------------

    private WorkReportServiceExtendedModel model(List<WorkReportRoomModel> rows) {
        WorkReportServiceExtendedModel model = new WorkReportServiceExtendedModel();
        model.setProjectId(PROJECT_ID);
        model.setWorkItemId(WORK_ITEM_ID);
        model.setWorkerId(WORKER_ID);
        model.setPerformedFrom(LocalDate.of(2026, 1, 1));
        model.setPerformedTo(LocalDate.of(2026, 1, 2));
        model.setRooms(new ArrayList<>(rows));
        return model;
    }

    private static WorkReportRoomModel row(Long roomId, BigDecimal quantity, WorkReportInputMode mode) {
        return new WorkReportRoomModel(null, roomId, null, quantity, null, mode);
    }

    private EstimateLineRoomQtyEntity plan(Long roomId, BigDecimal quantity) {
        EstimateLineRoomQtyEntity rq = new EstimateLineRoomQtyEntity();
        rq.setRoom(referenceStub(RoomEntity.class, roomId));
        rq.setQuantity(quantity);
        return rq;
    }

    private void stubEstimateLine(BigDecimal unitPrice, List<EstimateLineRoomQtyEntity> plans) {
        EstimateLineEntity line = new EstimateLineEntity();
        WorkItemEntity wi = new WorkItemEntity();
        wi.setId(WORK_ITEM_ID);
        WorkCategoryEntity cat = new WorkCategoryEntity();
        cat.setId(CATEGORY_ID);
        wi.setWorkCategory(cat);
        line.setWorkItem(wi);
        line.setUnitPrice(unitPrice);
        line.setRoomQtys(new ArrayList<>(plans));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of(line));
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
        verify(workReportDao, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static void authenticateWorker() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                String.valueOf(WORKER_ID), "n/a", List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
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
}
