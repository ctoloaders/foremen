package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.WorkReportRoomModel;
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
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.WorkReportServiceExtendedModel;
import com.foremen.service.model.mapper.WorkReportServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-01 task 7.2 — plain Mockito unit tests for {@link WorkReportService}, the revenue-side work
 * reporting service. The DAOs, mapper, event publisher, and {@link EntityManager} are mocked; the
 * caller user id / role authority is driven through {@link SecurityContextHolder}, mirroring
 * {@code ProjectScheduleServiceTest} / {@code ProjectMemberServiceTest}.
 *
 * <p>Covers the design's correctness properties on the service's own logic:
 * <ul>
 *   <li><b>P1 (totals)</b> — {@code totalQuantity = Σ rooms.quantity};
 *       {@code revenueValue = round2(clientUnitPrice × totalQuantity)}, always derived (R1.4/R1.5);</li>
 *   <li><b>P2 (percent resolution)</b> — a PERCENT row resolves to {@code plan × percent / 100};
 *       PERCENT-with-no-plan is rejected (R2.2/R2.3);</li>
 *   <li><b>P3 (status machine)</b> — only the §3.3 transitions are permitted; APPROVED/REJECTED are
 *       terminal; illegal transitions raise {@code error.workReport.status.illegalTransition} (R4.6);</li>
 *   <li><b>P4 (fast path)</b> — a FOREMAN author's report is created APPROVED with
 *       {@code approvedBy = author} and emits no notification (R5);</li>
 *   <li><b>P5 (work matching)</b> — a report persists only when the work item is on the estimate and
 *       {@code (worker, workItem)} is a Covering_Work (R3.2/R3.4);</li>
 *   <li><b>P6 (revenue freeze)</b> — an APPROVED report is immutable (R6.4);</li>
 *   <li>the WORKER own-records row scope and the FOREMAN/ADMIN-only review rule (R10.5, R4.7).</li>
 * </ul>
 *
 * <p>The committed {@code mediaRefs}/read mapping is irrelevant to these behaviors, so the mapper's
 * {@code toServiceExtendedModel} is stubbed to echo a minimal model.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkReportServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long WORK_ITEM_ID = 7L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long ROOM_A = 100L;
    private static final Long ROOM_B = 200L;
    private static final Long WORKER_ID = 500L;
    private static final Long FOREMAN_ID = 600L;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("12.50");

    @Mock
    private WorkReportDao workReportDao;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private EstimateLineDao estimateLineDao;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private WorkReportServiceMapper serviceMapper;
    @Mock
    private com.foremen.service.audit.AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private WorkReportService service;

    @BeforeEach
    void setUp() {
        service = new WorkReportService(
                workReportDao, projectMemberDao, estimateLineDao, projectAccessCache,
                serviceMapper, auditLogDao, entityManager, eventPublisher);
        // save() echoes its argument so the committed entity is the one under test.
        lenient().when(workReportDao.save(any(WorkReportEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // getReference(type, id) returns a lightweight stub carrying only its id.
        lenient().when(entityManager.getReference(any(), any()))
                .thenAnswer(inv -> referenceStub(inv.getArgument(0), (Long) inv.getArgument(1)));
        // The read mapping is not under test; echo a trivial model so create()/update() return non-null.
        lenient().when(serviceMapper.toServiceExtendedModel(any()))
                .thenReturn(new WorkReportServiceExtendedModel());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =======================================================================
    // P1 (totals) + P2 (percent resolution) via create
    // =======================================================================

    @Nested
    @DisplayName("derivations — P1 totals, P2 percent resolution (R1.4/R1.5/R2.2/R2.3)")
    class Derivations {

        @Test
        @DisplayName("totalQuantity = Σ rooms.quantity and revenueValue = round2(unitPrice × total) over ABSOLUTE rows (P1)")
        void absoluteRowsDeriveTotalsAndRevenue() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "10"), planFor(ROOM_B, "5"));

            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "3.000", WorkReportInputMode.ABSOLUTE),
                    row(ROOM_B, "4.500", WorkReportInputMode.ABSOLUTE));

            service.create(model);

            WorkReportEntity saved = capturedSave();
            // Σ = 3.000 + 4.500 = 7.500
            assertThat(saved.getTotalQuantity()).isEqualByComparingTo("7.500");
            assertThat(saved.getClientUnitPrice()).isEqualByComparingTo(UNIT_PRICE);
            // round2(12.50 × 7.500) = round2(93.750) = 93.75
            assertThat(saved.getRevenueValue()).isEqualByComparingTo("93.75");
        }

        @Test
        @DisplayName("a PERCENT row resolves to plan × percent / 100 and snapshots planQuantity (P2)")
        void percentRowResolvesAgainstPlan() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "20"));

            // 25% of a plan of 20 = 5.000
            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "25", WorkReportInputMode.PERCENT));

            service.create(model);

            WorkReportEntity saved = capturedSave();
            assertThat(saved.getRooms()).singleElement().satisfies(r -> {
                assertThat(r.getQuantity()).isEqualByComparingTo("5.000");
                assertThat(r.getPlanQuantity()).isEqualByComparingTo("20");
                assertThat(r.getInputMode()).isEqualTo(WorkReportInputMode.PERCENT);
            });
            assertThat(saved.getTotalQuantity()).isEqualByComparingTo("5.000");
            assertThat(saved.getRevenueValue()).isEqualByComparingTo("62.50"); // 12.50 × 5
        }

        @Test
        @DisplayName("a PERCENT row with no plan for the room is rejected with error.workReport.room.noPlan (P2)")
        void percentWithNoPlanRejected() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(/* no room plans */);

            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "25", WorkReportInputMode.PERCENT));

            assertApiError(() -> service.create(model),
                    HttpStatus.BAD_REQUEST, "error.workReport.room.noPlan");
            verify(workReportDao, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate room across rows is rejected with error.workReport.room.duplicate")
        void duplicateRoomRejected() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "10"));

            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE),
                    row(ROOM_A, "4", WorkReportInputMode.ABSOLUTE));

            assertApiError(() -> service.create(model),
                    HttpStatus.BAD_REQUEST, "error.workReport.room.duplicate");
            verify(workReportDao, never()).save(any());
        }

        @Test
        @DisplayName("a non-positive quantity is rejected with error.workReport.quantity.invalid")
        void nonPositiveQuantityRejected() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "10"));

            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "0", WorkReportInputMode.ABSOLUTE));

            assertApiError(() -> service.create(model),
                    HttpStatus.BAD_REQUEST, "error.workReport.quantity.invalid");
            verify(workReportDao, never()).save(any());
        }
    }

    // =======================================================================
    // P5 (work matching)
    // =======================================================================

    @Nested
    @DisplayName("work matching — P5 (R3.2/R3.4)")
    class WorkMatching {

        @Test
        @DisplayName("a work item not on the estimate is rejected with error.workReport.workItem.notInEstimate")
        void workItemNotOnEstimateRejected() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            // Estimate has a line for a DIFFERENT work item id, so WORK_ITEM_ID is not present.
            EstimateLineEntity other = new EstimateLineEntity();
            other.setWorkItem(workItem(9999L, CATEGORY_ID));
            when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                    .thenReturn(List.of(other));

            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));

            assertApiError(() -> service.create(model),
                    HttpStatus.BAD_REQUEST, "error.workReport.workItem.notInEstimate");
            verify(workReportDao, never()).save(any());
        }

        @Test
        @DisplayName("a (worker, workItem) that is not a Covering_Work is rejected with error.workReport.workItem.notCovered")
        void notCoveringWorkRejected() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            // The worker member covers a DIFFERENT category than the work item's.
            stubWorkerMember(categoryStub(9998L));
            stubEstimateLine(planFor(ROOM_A, "10"));

            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));

            assertApiError(() -> service.create(model),
                    HttpStatus.BAD_REQUEST, "error.workReport.workItem.notCovered");
            verify(workReportDao, never()).save(any());
        }
    }

    // =======================================================================
    // P4 (fast path) + initial status (R4.1)
    // =======================================================================

    @Nested
    @DisplayName("initial status + fast path — P4 (R4.1/R5)")
    class InitialStatus {

        @Test
        @DisplayName("a non-FOREMAN author's report is created SUBMITTED and notifies (R4.1/R9.1)")
        void nonForemanCreateIsSubmittedAndNotifies() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "10"));

            service.create(createModel(row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE)));

            WorkReportEntity saved = capturedSave();
            assertThat(saved.getStatus()).isEqualTo(WorkReportStatus.SUBMITTED);
            assertThat(saved.getApprovedBy()).isNull();
            assertThat(saved.getApprovedAt()).isNull();
            // A submit publishes exactly one notification event (R9.1).
            verify(eventPublisher).publishEvent(any(WorkReportNotificationEvent.class));
        }

        @Test
        @DisplayName("a FOREMAN author's report is created APPROVED with approvedBy=author and emits no notification (P4/R5)")
        void foremanFastPathIsApprovedAndSilent() {
            authenticate(FOREMAN_ID, "ROLE_FOREMAN");
            // The author (FOREMAN) is the project member whose role resolves the fast path; the
            // worker is a different user covered for the work item.
            stubProjectRole(FOREMAN_ID, "FOREMAN");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "10"));

            // A FOREMAN reports on another worker's behalf (worker != author) — fast path still applies.
            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "4", WorkReportInputMode.ABSOLUTE));

            service.create(model);

            WorkReportEntity saved = capturedSave();
            assertThat(saved.getStatus()).isEqualTo(WorkReportStatus.APPROVED);
            assertThat(saved.getApprovedBy()).isNotNull();
            assertThat(saved.getApprovedBy().getId()).isEqualTo(FOREMAN_ID);
            assertThat(saved.getApprovedAt()).isNotNull();
            // Revenue is frozen exactly as a normal approval (R5.4).
            assertThat(saved.getRevenueValue()).isEqualByComparingTo("50.00"); // 12.50 × 4
            // The fast path emits NO notification (R9.5).
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("a MANAGER holds no CREATE grant — a MANAGER create is rejected with error.workReport.create.forbidden (R10.7)")
        void managerCannotCreate() {
            authenticate(700L, "ROLE_MANAGER");
            stubProjectRole(700L, "MANAGER");

            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));

            assertApiError(() -> service.create(model),
                    HttpStatus.FORBIDDEN, "error.workReport.create.forbidden");
            verify(workReportDao, never()).save(any());
        }

        @Test
        @DisplayName("a WORKER create for another worker is rejected with error.workReport.worker.notSelf (R10.5)")
        void workerCannotCreateForAnother() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubProjectRole(WORKER_ID, "WORKER");

            // worker = a DIFFERENT user than the authenticated WORKER caller.
            WorkReportServiceExtendedModel model = createModel(
                    row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));
            model.setWorkerId(999_000L);

            assertApiError(() -> service.create(model),
                    HttpStatus.FORBIDDEN, "error.workReport.worker.notSelf");
            verify(workReportDao, never()).save(any());
        }
    }

    // =======================================================================
    // P3 (status machine) + P6 (revenue freeze) via the review transitions
    // =======================================================================

    @Nested
    @DisplayName("status machine + revenue freeze — P3 / P6 (R4.x / R6.4)")
    class StatusMachine {

        @Test
        @DisplayName("an ADMIN approves a SUBMITTED report → APPROVED, approvedBy/approvedAt set (R4.2)")
        void approveSubmitted() {
            authenticate(1L, "ROLE_ADMIN"); // ADMIN bypasses project-access + is a valid approver
            WorkReportEntity report = persistedReport(WorkReportStatus.SUBMITTED);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));

            service.approve(report.getId());

            assertThat(report.getStatus()).isEqualTo(WorkReportStatus.APPROVED);
            assertThat(report.getApprovedBy()).isNotNull();
            assertThat(report.getApprovedAt()).isNotNull();
            // Approval notifies the author (R9.2).
            verify(eventPublisher).publishEvent(any(WorkReportNotificationEvent.class));
        }

        @Test
        @DisplayName("approving an already-REJECTED (terminal) report is refused with illegalTransition (P3/R4.6)")
        void approveRejectedIsIllegal() {
            authenticate(1L, "ROLE_ADMIN");
            WorkReportEntity report = persistedReport(WorkReportStatus.REJECTED);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));

            assertApiError(() -> service.approve(report.getId()),
                    HttpStatus.CONFLICT, "error.workReport.status.illegalTransition");
            assertThat(report.getStatus()).isEqualTo(WorkReportStatus.REJECTED); // unchanged
        }

        @Test
        @DisplayName("approving an already-APPROVED (terminal) report is refused with illegalTransition (P3/R4.6)")
        void approveApprovedIsIllegal() {
            authenticate(1L, "ROLE_ADMIN");
            WorkReportEntity report = persistedReport(WorkReportStatus.APPROVED);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));

            assertApiError(() -> service.approve(report.getId()),
                    HttpStatus.CONFLICT, "error.workReport.status.illegalTransition");
        }

        @Test
        @DisplayName("rejecting a SUBMITTED report requires a reason; a blank reason is refused (R4.3)")
        void rejectRequiresReason() {
            authenticate(1L, "ROLE_ADMIN");
            WorkReportEntity report = persistedReport(WorkReportStatus.SUBMITTED);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));

            assertApiError(() -> service.reject(report.getId(), "   "),
                    HttpStatus.BAD_REQUEST, "error.workReport.rejection.reasonRequired");
            assertThat(report.getStatus()).isEqualTo(WorkReportStatus.SUBMITTED); // unchanged
        }

        @Test
        @DisplayName("rejecting a SUBMITTED report with a reason → REJECTED (terminal) and notifies (R4.3/R9.3)")
        void rejectWithReason() {
            authenticate(1L, "ROLE_ADMIN");
            WorkReportEntity report = persistedReport(WorkReportStatus.SUBMITTED);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));

            service.reject(report.getId(), "incomplete");

            assertThat(report.getStatus()).isEqualTo(WorkReportStatus.REJECTED);
            assertThat(report.getRejectionReason()).isEqualTo("incomplete");
            verify(eventPublisher).publishEvent(any(WorkReportNotificationEvent.class));
        }

        @Test
        @DisplayName("requesting changes on a SUBMITTED report → CHANGES_REQUESTED with reason (R4.4)")
        void requestChanges() {
            authenticate(1L, "ROLE_ADMIN");
            WorkReportEntity report = persistedReport(WorkReportStatus.SUBMITTED);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));

            service.requestChanges(report.getId(), "add photos");

            assertThat(report.getStatus()).isEqualTo(WorkReportStatus.CHANGES_REQUESTED);
            assertThat(report.getRejectionReason()).isEqualTo("add photos");
            verify(eventPublisher).publishEvent(any(WorkReportNotificationEvent.class));
        }

        @Test
        @DisplayName("a WORKER may never approve, even their own report (R4.7)")
        void workerCannotApprove() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            when(projectAccessCache.get(WORKER_ID)).thenReturn(java.util.Set.of(PROJECT_ID));
            WorkReportEntity report = persistedReport(WorkReportStatus.SUBMITTED);
            // getProjectId(id) is resolved by the base ProjectScopedService via a Criteria query;
            // stub the entity manager path to return this project so access passes and the review
            // role check (not access) is what rejects.
            stubProjectIdResolution(report.getId(), PROJECT_ID);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));
            // The caller is a WORKER member (not an ACTIVE FOREMAN), so assertApprover rejects.
            stubMember(WORKER_ID, "WORKER", AssignmentStatus.ACTIVE);

            assertApiError(() -> service.approve(report.getId()),
                    HttpStatus.FORBIDDEN, "error.workReport.review.forbidden");
            assertThat(report.getStatus()).isEqualTo(WorkReportStatus.SUBMITTED);
        }

        @Test
        @DisplayName("editing an APPROVED report is refused — revenue + rows frozen (P6/R6.4/R4.6)")
        void editApprovedIsRefused() {
            authenticate(1L, "ROLE_ADMIN"); // ADMIN bypasses project-access; the terminal guard still trips
            WorkReportEntity report = persistedReport(WorkReportStatus.APPROVED);
            when(workReportDao.findById(report.getId())).thenReturn(Optional.of(report));

            WorkReportServiceExtendedModel edit = createModel(row(ROOM_A, "9", WorkReportInputMode.ABSOLUTE));

            assertApiError(() -> service.update(report.getId(), edit),
                    HttpStatus.CONFLICT, "error.workReport.status.illegalTransition");
            // The report is untouched (revenue freeze, P6).
            assertThat(report.getStatus()).isEqualTo(WorkReportStatus.APPROVED);
            assertThat(report.getRevenueValue()).isEqualByComparingTo("50.00");
        }
    }

    // ========================================================================
    // test-data builders / stubs
    // ========================================================================

    private WorkReportServiceExtendedModel createModel(WorkReportRoomModel... rows) {
        WorkReportServiceExtendedModel model = new WorkReportServiceExtendedModel();
        model.setProjectId(PROJECT_ID);
        model.setWorkItemId(WORK_ITEM_ID);
        model.setWorkerId(WORKER_ID);
        model.setPerformedFrom(LocalDate.of(2026, 1, 1));
        model.setPerformedTo(LocalDate.of(2026, 1, 2));
        model.setRooms(new ArrayList<>(List.of(rows)));
        return model;
    }

    private static WorkReportRoomModel row(Long roomId, String quantity, WorkReportInputMode mode) {
        return new WorkReportRoomModel(null, roomId, null, new BigDecimal(quantity), null, mode);
    }

    /** A persisted report in the given status, with the revenue valuation pre-frozen (12.50 × 4 = 50.00). */
    private WorkReportEntity persistedReport(WorkReportStatus status) {
        WorkReportEntity report = new WorkReportEntity();
        report.setId(900L);
        report.setProject(referenceStub(ProjectEntity.class, PROJECT_ID));
        report.setWorkItem(workItem(WORK_ITEM_ID, CATEGORY_ID));
        report.setWorker(referenceStub(UserEntity.class, WORKER_ID));
        report.setAuthor(referenceStub(UserEntity.class, WORKER_ID));
        report.setStatus(status);
        report.setClientUnitPrice(UNIT_PRICE);
        report.setTotalQuantity(new BigDecimal("4.000"));
        report.setRevenueValue(new BigDecimal("50.00"));
        return report;
    }

    private EstimateLineRoomQtyEntity planFor(Long roomId, String quantity) {
        EstimateLineRoomQtyEntity rq = new EstimateLineRoomQtyEntity();
        rq.setRoom(referenceStub(RoomEntity.class, roomId));
        rq.setQuantity(new BigDecimal(quantity));
        return rq;
    }

    /** Stubs the project's estimate as a single line for WORK_ITEM_ID carrying the given room plans. */
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
        wi.setWorkCategory(categoryStub(categoryId));
        return wi;
    }

    private static WorkCategoryEntity categoryStub(Long id) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(id);
        return c;
    }

    /** Stubs the worker's project membership as covering the work item's category (CATEGORY_ID). */
    private void stubWorkerMember(WorkCategoryEntity... coveredOverride) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(referenceStub(UserEntity.class, WORKER_ID));
        member.setProjectId(PROJECT_ID);
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        RoleEntity role = new RoleEntity();
        role.setCode("WORKER");
        member.setProjectRole(role);
        List<WorkCategoryEntity> covered = coveredOverride.length > 0
                ? List.of(coveredOverride) : List.of(categoryStub(CATEGORY_ID));
        member.setWorkCategories(new ArrayList<>(covered));
        when(projectMemberDao.findByUserIdAndProjectId(WORKER_ID, PROJECT_ID))
                .thenReturn(Optional.of(member));
    }

    /** Stubs the author's project role lookup (drives the fast path / MANAGER-no-create / worker-self). */
    private void stubProjectRole(Long userId, String roleCode) {
        stubMember(userId, roleCode, AssignmentStatus.ACTIVE);
    }

    private void stubMember(Long userId, String roleCode, AssignmentStatus status) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(referenceStub(UserEntity.class, userId));
        member.setProjectId(PROJECT_ID);
        member.setAssignmentStatus(status);
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        member.setProjectRole(role);
        when(projectMemberDao.findByUserIdAndProjectId(userId, PROJECT_ID))
                .thenReturn(Optional.of(member));
    }

    /**
     * Stubs the base {@link ProjectScopedService#getProjectId(Object)} Criteria resolution so a
     * non-ADMIN review path passes the project-access gate and reaches the review-role check. The
     * resolver builds a {@code CriteriaQuery<Long>} selecting the {@code "project.id"} path (one
     * {@code join("project")} then {@code .get("id")}) and reads a single-row result list.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubProjectIdResolution(Long reportId, Long projectId) {
        when(projectAccessCache.get(any())).thenReturn(java.util.Set.of(projectId));
        var cb = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        var cq = org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaQuery.class);
        var root = org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        var join = org.mockito.Mockito.mock(jakarta.persistence.criteria.Join.class);
        var path = org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        var idExpr = org.mockito.Mockito.mock(jakarta.persistence.criteria.Expression.class);
        var predicate = org.mockito.Mockito.mock(jakarta.persistence.criteria.Predicate.class);
        var typed = org.mockito.Mockito.mock(jakarta.persistence.TypedQuery.class);
        lenient().when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        lenient().when(cb.createQuery(Long.class)).thenReturn(cq);
        lenient().when(cq.from(any(Class.class))).thenReturn(root);
        // resolvePath("project.id"): from.join("project").get("id")
        lenient().when(root.join(any(String.class))).thenReturn(join);
        lenient().when(join.get(any(String.class))).thenReturn(path);
        // getProjectId also calls root.get("id") for the where-clause id equality.
        lenient().when(root.get(any(String.class))).thenReturn(path);
        lenient().when(path.as(Long.class)).thenReturn(idExpr);
        lenient().when(cb.equal(any(jakarta.persistence.criteria.Expression.class), any()))
                .thenReturn(predicate);
        lenient().when(cq.select(any())).thenReturn(cq);
        lenient().when(cq.where(any(jakarta.persistence.criteria.Predicate.class))).thenReturn(cq);
        lenient().when(entityManager.createQuery(cq)).thenReturn(typed);
        lenient().when(typed.setMaxResults(1)).thenReturn(typed);
        lenient().when(typed.getResultList()).thenReturn(List.of(projectId));
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

    private static void assertApiError(Runnable action, HttpStatus status, String messageCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(status);
                    assertThat(api.getMessageCode()).isEqualTo(messageCode);
                });
    }
}
