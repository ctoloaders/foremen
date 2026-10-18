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

import com.foremen.controller.model.WorkReportProgressDto;
import com.foremen.controller.model.WorkReportRoomModel;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
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
import com.foremen.exception.WorkReportBatchItemException;
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

    // =======================================================================
    // Amendment A1 — progress read projection (R14)
    // =======================================================================

    @Nested
    @DisplayName("progress projection — A1 (R14.2/R14.4/R14.6)")
    class ProgressProjection {

        @Test
        @DisplayName("reportedQuantity sums SUBMITTED + CHANGES_REQUESTED + APPROVED and excludes REJECTED (R14.2)")
        void reportedQuantityCountsPendingAndApprovedExcludesRejected() {
            authenticate(1L, "ROLE_ADMIN"); // ADMIN bypasses the project-scope + own-records gates.
            stubCoveringCategories(CATEGORY_ID);
            stubProgressEstimate(progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD",
                    planFor(ROOM_A, "40"), planFor(ROOM_B, "20")));

            // The DAO already folds in only the {SUBMITTED, CHANGES_REQUESTED, APPROVED} statuses
            // (REJECTED excluded) via the passed status set; its summed rows are what the service maps.
            stubReportedSums(
                    reportedRow(WORK_ITEM_ID, ROOM_A, "25"),   // e.g. 10 approved + 15 pending
                    reportedRow(WORK_ITEM_ID, ROOM_B, "5"));

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            // The exclusion is enforced by the status set the service hands the DAO (R14.2).
            assertThat(capturedReportedStatuses())
                    .containsExactlyInAnyOrder(
                            WorkReportStatus.SUBMITTED,
                            WorkReportStatus.CHANGES_REQUESTED,
                            WorkReportStatus.APPROVED)
                    .doesNotContain(WorkReportStatus.REJECTED);

            WorkReportProgressDto.Cell roomA = cell(dto, WORK_ITEM_ID, ROOM_A);
            WorkReportProgressDto.Cell roomB = cell(dto, WORK_ITEM_ID, ROOM_B);
            assertThat(roomA.reportedQuantity()).isEqualByComparingTo("25");
            assertThat(roomA.planQuantity()).isEqualByComparingTo("40");
            assertThat(roomA.reportedPercent()).isEqualTo(63); // round(25/40×100) = 62.5 → 63
            assertThat(roomB.reportedQuantity()).isEqualByComparingTo("5");
            assertThat(roomB.reportedPercent()).isEqualTo(25); // 5/20×100
        }

        @Test
        @DisplayName("a (workItem, room) with no matching reports reads reportedQuantity 0 (R14.2)")
        void unreportedCellIsZero() {
            authenticate(1L, "ROLE_ADMIN");
            stubCoveringCategories(CATEGORY_ID);
            stubProgressEstimate(progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD",
                    planFor(ROOM_A, "40")));
            stubReportedSums(/* nothing reported yet */);

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            WorkReportProgressDto.Cell roomA = cell(dto, WORK_ITEM_ID, ROOM_A);
            assertThat(roomA.reportedQuantity()).isEqualByComparingTo("0");
            assertThat(roomA.reportedPercent()).isEqualTo(0);
        }

        @Test
        @DisplayName("a work item whose every planned room is ≥100% is omitted from the matrix (R14.4)")
        void fullyReportedWorkItemOmitted() {
            authenticate(1L, "ROLE_ADMIN");
            stubCoveringCategories(CATEGORY_ID);
            // Two work items in the same covered category: one fully reported, one partial.
            Long partialItem = 8L;
            stubProgressEstimate(
                    progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD",
                            planFor(ROOM_A, "40"), planFor(ROOM_B, "20")),
                    progressLine(partialItem, CATEGORY_ID, 20, "ORD",
                            planFor(ROOM_A, "40")));
            stubReportedSums(
                    // WORK_ITEM_ID — both planned rooms at or over 100% → omitted.
                    reportedRow(WORK_ITEM_ID, ROOM_A, "40"),
                    reportedRow(WORK_ITEM_ID, ROOM_B, "25"),
                    // partialItem — still below 100% → kept.
                    reportedRow(partialItem, ROOM_A, "10"));

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            assertThat(workItemIds(dto)).containsExactly(partialItem).doesNotContain(WORK_ITEM_ID);
        }

        @Test
        @DisplayName("a work item with a not-yet-complete planned room is kept (R14.4)")
        void partiallyReportedWorkItemKept() {
            authenticate(1L, "ROLE_ADMIN");
            stubCoveringCategories(CATEGORY_ID);
            stubProgressEstimate(progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD",
                    planFor(ROOM_A, "40"), planFor(ROOM_B, "20")));
            stubReportedSums(
                    reportedRow(WORK_ITEM_ID, ROOM_A, "40"),   // 100%
                    reportedRow(WORK_ITEM_ID, ROOM_B, "5"));   // 25% → not complete → keep the row

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            assertThat(workItemIds(dto)).containsExactly(WORK_ITEM_ID);
            assertThat(cell(dto, WORK_ITEM_ID, ROOM_A).reportedPercent()).isEqualTo(100);
            assertThat(cell(dto, WORK_ITEM_ID, ROOM_B).reportedPercent()).isEqualTo(25);
        }

        @Test
        @DisplayName("an over-reported room caps reportedPercent at 100 (R14.3)")
        void reportedPercentCappedAtHundred() {
            authenticate(1L, "ROLE_ADMIN");
            stubCoveringCategories(CATEGORY_ID);
            // One over-reported planned room and one still-open room so the row is kept.
            stubProgressEstimate(progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD",
                    planFor(ROOM_A, "40"), planFor(ROOM_B, "20")));
            stubReportedSums(
                    reportedRow(WORK_ITEM_ID, ROOM_A, "60"),   // 150% → capped to 100
                    reportedRow(WORK_ITEM_ID, ROOM_B, "0"));

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            assertThat(cell(dto, WORK_ITEM_ID, ROOM_A).reportedPercent()).isEqualTo(100);
        }

        @Test
        @DisplayName("a work item with no plan for a room exposes a null-plan absolute-only cell (R14.3)")
        void noPlanRoomHasNullPercent() {
            authenticate(1L, "ROLE_ADMIN");
            stubCoveringCategories(CATEGORY_ID);
            stubProgressEstimate(progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD",
                    planForNoQty(ROOM_A)));
            stubReportedSums(reportedRow(WORK_ITEM_ID, ROOM_A, "7"));

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            WorkReportProgressDto.Cell roomA = cell(dto, WORK_ITEM_ID, ROOM_A);
            assertThat(roomA.planQuantity()).isNull();
            assertThat(roomA.reportedPercent()).isNull();
            assertThat(roomA.reportedQuantity()).isEqualByComparingTo("7");
        }

        @Test
        @DisplayName("a worker who covers no categories yields an empty matrix (R14.5)")
        void emptyWhenWorkerCoversNothing() {
            authenticate(1L, "ROLE_ADMIN");
            stubCoveringCategories(/* no covered categories */);

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            assertThat(dto.categories()).isEmpty();
            assertThat(dto.rooms()).isEmpty();
        }

        @Test
        @DisplayName("an estimate work item outside the worker's Covering_Works is excluded (R3.1/R14.4)")
        void nonCoveringWorkItemExcluded() {
            authenticate(1L, "ROLE_ADMIN");
            stubCoveringCategories(CATEGORY_ID);
            Long otherCategory = 9999L;
            stubProgressEstimate(
                    progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD", planFor(ROOM_A, "40")),
                    progressLine(123L, otherCategory, 20, "ORD", planFor(ROOM_B, "40")));
            stubReportedSums();

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            assertThat(workItemIds(dto)).containsExactly(WORK_ITEM_ID).doesNotContain(123L);
        }

        @Test
        @DisplayName("a WORKER caller requesting another worker's progress is refused with notOwner (R14.6/R10.5)")
        void workerCannotRequestAnother() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            when(projectAccessCache.get(WORKER_ID)).thenReturn(java.util.Set.of(PROJECT_ID));

            assertApiError(() -> service.progress(PROJECT_ID, 999_000L),
                    HttpStatus.FORBIDDEN, "error.workReport.access.notOwner");
        }

        @Test
        @DisplayName("a WORKER caller may request their own progress (R14.6)")
        void workerMayRequestOwn() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            when(projectAccessCache.get(WORKER_ID)).thenReturn(java.util.Set.of(PROJECT_ID));
            stubCoveringCategories(CATEGORY_ID);
            stubProgressEstimate(progressLine(WORK_ITEM_ID, CATEGORY_ID, 10, "ORD",
                    planFor(ROOM_A, "40")));
            stubReportedSums(reportedRow(WORK_ITEM_ID, ROOM_A, "5"));

            WorkReportProgressDto dto = service.progress(PROJECT_ID, WORKER_ID);

            assertThat(dto.workerId()).isEqualTo(WORKER_ID);
            assertThat(workItemIds(dto)).containsExactly(WORK_ITEM_ID);
        }
    }

    // =======================================================================
    // Amendment A1 — bulk create (R16)
    // =======================================================================

    @Nested
    @DisplayName("createBatch — A1 (R16.3/R16.4/R16.5)")
    class BatchCreate {

        @Test
        @DisplayName("a batch of valid reports persists every item and emits one notification per non-FOREMAN report (R16.3/R16.5)")
        void batchPersistsAllAndNotifiesPerReport() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "40"), planFor(ROOM_B, "40"));

            WorkReportServiceExtendedModel first =
                    createModel(row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));
            WorkReportServiceExtendedModel second =
                    createModel(row(ROOM_B, "4", WorkReportInputMode.ABSOLUTE));

            List<WorkReportServiceExtendedModel> result =
                    service.createBatch(List.of(first, second));

            // Both items persisted (one save each).
            assertThat(result).hasSize(2);
            verify(workReportDao, org.mockito.Mockito.times(2)).save(any(WorkReportEntity.class));
            // One submitted-notification event per non-FOREMAN report (R16.5).
            verify(eventPublisher, org.mockito.Mockito.times(2))
                    .publishEvent(any(WorkReportNotificationEvent.class));
        }

        @Test
        @DisplayName("FOREMAN-authored fast-path items persist but emit no notification (R5/R16.5)")
        void fastPathItemsEmitNoNotification() {
            authenticate(FOREMAN_ID, "ROLE_FOREMAN");
            stubProjectRole(FOREMAN_ID, "FOREMAN");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "40"), planFor(ROOM_B, "40"));

            WorkReportServiceExtendedModel first =
                    createModel(row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));
            WorkReportServiceExtendedModel second =
                    createModel(row(ROOM_B, "4", WorkReportInputMode.ABSOLUTE));

            List<WorkReportServiceExtendedModel> result =
                    service.createBatch(List.of(first, second));

            assertThat(result).hasSize(2);
            verify(workReportDao, org.mockito.Mockito.times(2)).save(any(WorkReportEntity.class));
            // The fast path emits nothing for either item (R9.5).
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("a single bad item rolls back the whole batch — nothing persists, no event fires (R16.4)")
        void badItemRollsBackWholeBatch() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            // ROOM_A has a plan; ROOM_B has none, so a PERCENT row on ROOM_B is invalid (R2.3).
            stubEstimateLine(planFor(ROOM_A, "40"));

            WorkReportServiceExtendedModel good =
                    createModel(row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));
            WorkReportServiceExtendedModel bad =
                    createModel(row(ROOM_B, "50", WorkReportInputMode.PERCENT)); // no plan → noPlan

            assertThatThrownBy(() -> service.createBatch(List.of(good, bad)))
                    .isInstanceOf(WorkReportBatchItemException.class)
                    .satisfies(ex -> {
                        WorkReportBatchItemException batch = (WorkReportBatchItemException) ex;
                        assertThat(batch.getIndex()).isEqualTo(1); // the second (zero-based) item
                        assertThat(batch.getWorkItemId()).isEqualTo(WORK_ITEM_ID);
                        assertThat(batch.getRoomId()).isEqualTo(ROOM_B); // room-scoped failure
                        assertThat(batch.getCause().getMessageCode())
                                .isEqualTo("error.workReport.room.noPlan");
                    });

            // The good item was assembled before the bad one failed; the outer @Transactional rolls
            // the whole batch back, but the per-item notification events are AFTER_COMMIT, so on a
            // rollback NO event is ever published (R16.4).
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("the failing item index points to the first bad payload regardless of position (R16.4)")
        void failingIndexIdentifiesFirstBadItem() {
            authenticate(WORKER_ID, "ROLE_WORKER");
            stubWorkerMember();
            stubEstimateLine(planFor(ROOM_A, "40"));

            // index 0 ok, index 1 bad (non-positive quantity, R2.5), index 2 would also be ok.
            WorkReportServiceExtendedModel ok =
                    createModel(row(ROOM_A, "3", WorkReportInputMode.ABSOLUTE));
            WorkReportServiceExtendedModel bad =
                    createModel(row(ROOM_A, "0", WorkReportInputMode.ABSOLUTE));
            WorkReportServiceExtendedModel alsoOk =
                    createModel(row(ROOM_A, "2", WorkReportInputMode.ABSOLUTE));

            assertThatThrownBy(() -> service.createBatch(List.of(ok, bad, alsoOk)))
                    .isInstanceOf(WorkReportBatchItemException.class)
                    .satisfies(ex -> assertThat(((WorkReportBatchItemException) ex).getIndex())
                            .isEqualTo(1));
        }

        @Test
        @DisplayName("an empty batch persists nothing and returns an empty list (R16.3)")
        void emptyBatchIsNoOp() {
            authenticate(WORKER_ID, "ROLE_WORKER");

            assertThat(service.createBatch(List.of())).isEmpty();
            verify(workReportDao, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
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

    // --- progress-projection builders / stubs (A1) ---

    /** Stubs the worker's project membership to cover exactly the given category ids (Covering_Works R3.1). */
    private void stubCoveringCategories(Long... categoryIds) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(referenceStub(UserEntity.class, WORKER_ID));
        member.setProjectId(PROJECT_ID);
        member.setAssignmentStatus(AssignmentStatus.ACTIVE);
        List<WorkCategoryEntity> covered = new ArrayList<>();
        for (Long id : categoryIds) {
            covered.add(categoryWithOrder(id, 1, "CAT-" + id));
        }
        member.setWorkCategories(covered);
        when(projectMemberDao.findByUserIdAndProjectId(WORKER_ID, PROJECT_ID))
                .thenReturn(Optional.of(member));
    }

    /** Stubs the project's estimate lines (the progress projection's work-item source). */
    private void stubProgressEstimate(EstimateLineEntity... lines) {
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of(lines));
    }

    /** An estimate line for a named work item in a category (with orderNo), carrying the given room plans. */
    private EstimateLineEntity progressLine(Long workItemId, Long categoryId, int orderNo,
                                            String unitCode, EstimateLineRoomQtyEntity... plans) {
        WorkItemEntity wi = new WorkItemEntity();
        wi.setId(workItemId);
        wi.setNameRU("WI-RU-" + workItemId);
        wi.setNamePL("WI-PL-" + workItemId);
        wi.setCode("WI-" + workItemId);
        wi.setWorkCategory(categoryWithOrder(categoryId, orderNo, "CAT-" + categoryId));

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(unitCode);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setWorkItem(wi);
        line.setUnit(unit);
        line.setUnitPrice(UNIT_PRICE);
        line.setRoomQtys(new ArrayList<>(List.of(plans)));
        return line;
    }

    private static WorkCategoryEntity categoryWithOrder(Long id, int orderNo, String code) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(id);
        c.setOrderNo(orderNo);
        c.setCode(code);
        c.setNameRU("CAT-RU-" + id);
        c.setNamePL("CAT-PL-" + id);
        return c;
    }

    /** Stubs the reported-quantity aggregate the progress projection reads (DAO rows). */
    private void stubReportedSums(Object[]... rows) {
        when(workReportDao.sumReportedQuantityByWorkItemAndRoom(
                org.mockito.ArgumentMatchers.eq(PROJECT_ID),
                org.mockito.ArgumentMatchers.eq(WORKER_ID),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(rows));
    }

    /** A room-qty row with a null plan quantity (the no-plan / absolute-only cell case, R14.3). */
    private EstimateLineRoomQtyEntity planForNoQty(Long roomId) {
        EstimateLineRoomQtyEntity rq = new EstimateLineRoomQtyEntity();
        rq.setRoom(referenceStub(RoomEntity.class, roomId));
        rq.setQuantity(null);
        return rq;
    }

    /** One {@code [workItemId, roomId, reportedQuantity]} aggregate row. */
    private static Object[] reportedRow(Long workItemId, Long roomId, String quantity) {
        return new Object[] {workItemId, roomId, new BigDecimal(quantity)};
    }

    /** Captures the status set handed to the reported-sum DAO query (asserts REJECTED is excluded). */
    @SuppressWarnings("unchecked")
    private java.util.Collection<WorkReportStatus> capturedReportedStatuses() {
        ArgumentCaptor<java.util.Collection<WorkReportStatus>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(workReportDao).sumReportedQuantityByWorkItemAndRoom(
                org.mockito.ArgumentMatchers.eq(PROJECT_ID),
                org.mockito.ArgumentMatchers.eq(WORKER_ID),
                captor.capture());
        return captor.getValue();
    }

    /** All work-item ids present in the projection, flattened across its category groups. */
    private static List<Long> workItemIds(WorkReportProgressDto dto) {
        return dto.categories().stream()
                .flatMap(g -> g.workItems().stream())
                .map(WorkReportProgressDto.WorkItemRow::workItemId)
                .toList();
    }

    /** The cell for a given {@code (workItem, room)} in the projection; fails if absent. */
    private static WorkReportProgressDto.Cell cell(WorkReportProgressDto dto, Long workItemId, Long roomId) {
        return dto.categories().stream()
                .flatMap(g -> g.workItems().stream())
                .filter(w -> workItemId.equals(w.workItemId()))
                .flatMap(w -> w.cells().stream())
                .filter(c -> roomId.equals(c.roomId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no cell for workItem=" + workItemId + " room=" + roomId));
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
