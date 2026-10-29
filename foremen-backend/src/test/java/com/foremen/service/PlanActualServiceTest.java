package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.config.PlanActualProperties;
import com.foremen.controller.model.schedule.PlanActualCategory;
import com.foremen.controller.model.schedule.PlanActualView;
import com.foremen.controller.model.schedule.ScheduleBarView;
import com.foremen.controller.model.schedule.ScheduleRowView;
import com.foremen.controller.model.schedule.ScheduleView;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.WorkReportDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.service.schedule.TrajectoryCalculator.TrajectoryStatus;

/**
 * FOR-06-02 (task 5.2; design §7 P1, P3–P7, P9) — plain Mockito unit tests for
 * {@link PlanActualService}. {@link ProjectScheduleService} is mocked to supply a
 * crafted {@link ScheduleView} (plan framing + money gate), the estimate line DAO
 * supplies planned quantity per {@code (workItem, room)}, and the work-report DAO
 * supplies the bucketed reported quantity + drill-down refs. No DB / Spring
 * context — the join + math are the unit under test.
 */
@ExtendWith(MockitoExtension.class)
class PlanActualServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long CATEGORY_ID = 7L;
    private static final Long WORK_ITEM_ID = 1007L;
    private static final Long ROOM_ID = 3L;

    @Mock
    private ProjectScheduleService projectScheduleService;
    @Mock
    private EstimateLineDao estimateLineDao;
    @Mock
    private WorkReportDao workReportDao;

    private PlanActualService service;

    @BeforeEach
    void setUp() {
        PlanActualProperties props = new PlanActualProperties(0.1, 3650);
        service = new PlanActualService(projectScheduleService, estimateLineDao, workReportDao, props);
        authenticate("1", "ROLE_ADMIN");
        // A project with a calendar anchor far in the past so the category window is fully elapsed.
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        project.setStartDate(LocalDate.now().minusDays(30));
        lenient().when(projectScheduleService.loadAccessibleProject(anyLong())).thenReturn(project);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- scenarios ---------------------------------------------------------

    @Test
    @DisplayName("buckets reported quantity by status: APPROVED confirmed, SUBMITTED/CHANGES_REQUESTED pending, REJECTED ignored (P3)")
    void bucketsByStatus() {
        stubScheduleWithOneCategoryBar(/* startHalfDays */ 2, /* durationHalfDays */ 10, /* moneyViewer */ false);
        stubEstimate(new BigDecimal("100"), /* unitPrice */ new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID)).thenReturn(List.of(
                bucketRow(WorkReportStatus.APPROVED, new BigDecimal("40")),
                bucketRow(WorkReportStatus.SUBMITTED, new BigDecimal("15")),
                bucketRow(WorkReportStatus.CHANGES_REQUESTED, new BigDecimal("5")),
                bucketRow(WorkReportStatus.REJECTED, new BigDecimal("99"))));
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualView view = service.getPlanActual(PROJECT_ID);
        PlanActualCategory cat = view.categories().get(0);

        assertThat(cat.confirmedQuantity()).isEqualByComparingTo("40");
        assertThat(cat.pendingQuantity()).isEqualByComparingTo("20"); // 15 + 5
        assertThat(cat.rejectedQuantity()).isEqualByComparingTo("99"); // context only
        // Confirmed completion is 40/100 = 0.4; rejected never inflates it.
        assertThat(cat.confirmedCompletion()).isEqualTo(0.4);
        assertThat(view.totals().confirmedQuantity()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("over-report clamps completion at 1 and flags overReported (P4)")
    void overReportClamps() {
        stubScheduleWithOneCategoryBar(2, 10, false);
        stubEstimate(new BigDecimal("50"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID))
                .thenReturn(List.<Object[]>of(bucketRow(WorkReportStatus.APPROVED, new BigDecimal("80"))));
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualCategory cat = service.getPlanActual(PROJECT_ID).categories().get(0);
        assertThat(cat.confirmedCompletion()).isEqualTo(1.0);
        assertThat(cat.overReported()).isTrue();
        assertThat(cat.cells().get(0).overReported()).isTrue();
    }

    @Test
    @DisplayName("a plan-less cell with a confirmed actual is a fully-complete unplanned actual, flagged noPlan (P4)")
    void unplannedActual() {
        stubScheduleWithOneCategoryBar(2, 10, false);
        // No room quantity → the cell has no plan.
        stubEstimateNoPlan(new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID))
                .thenReturn(List.<Object[]>of(bucketRow(WorkReportStatus.APPROVED, new BigDecimal("12"))));
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualCategory cat = service.getPlanActual(PROJECT_ID).categories().get(0);
        assertThat(cat.noPlan()).isTrue();
        assertThat(cat.cells().get(0).noPlan()).isTrue();
        assertThat(cat.confirmedCompletion()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("time up and not complete → OVERDUE; trajectory ignores pending (P5)")
    void overdueFromConfirmedOnly() {
        // Window fully elapsed (anchor 30 days ago, bar starts at day 1 for 5 days).
        stubScheduleWithOneCategoryBar(2, 10, false);
        stubEstimate(new BigDecimal("100"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID)).thenReturn(List.of(
                bucketRow(WorkReportStatus.APPROVED, new BigDecimal("30")),
                // A large pending bucket must NOT rescue the trajectory.
                bucketRow(WorkReportStatus.SUBMITTED, new BigDecimal("70"))));
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualCategory cat = service.getPlanActual(PROJECT_ID).categories().get(0);
        assertThat(cat.trajectoryStatus()).isEqualTo(TrajectoryStatus.OVERDUE);
    }

    @Test
    @DisplayName("no calendar anchor → ON_TRACK with a noAnchor note (P6)")
    void noAnchorNote() {
        ProjectEntity noAnchor = project(ProjectStatus.ACTIVE); // startDate null
        when(projectScheduleService.loadAccessibleProject(PROJECT_ID)).thenReturn(noAnchor);
        stubScheduleNoAnchor(2, 10);
        stubEstimate(new BigDecimal("100"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID))
                .thenReturn(List.<Object[]>of(bucketRow(WorkReportStatus.APPROVED, new BigDecimal("0"))));
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualCategory cat = service.getPlanActual(PROJECT_ID).categories().get(0);
        assertThat(cat.trajectoryStatus()).isEqualTo(TrajectoryStatus.ON_TRACK);
        assertThat(cat.note()).isEqualTo("noAnchor");
        assertThat(cat.projectedFinishDay()).isNull();
    }

    @Test
    @DisplayName("empty plan (no bars) → ON_TRACK category rows with an empty window (P7)")
    void emptyPlan() {
        // A category row with no bars (nothing scheduled).
        ScheduleView view = new ScheduleView(
                PROJECT_ID, LocalDate.now().minusDays(30), null, false, 3L, 0, null, null, null, false,
                List.of(), List.of(row(List.of())));
        when(projectScheduleService.getView(PROJECT_ID)).thenReturn(view);
        stubEstimate(new BigDecimal("100"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID)).thenReturn(List.of());
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualCategory cat = service.getPlanActual(PROJECT_ID).categories().get(0);
        assertThat(cat.plannedDurationDays()).isZero();
        assertThat(cat.trajectoryStatus()).isEqualTo(TrajectoryStatus.ON_TRACK);
    }

    @Test
    @DisplayName("money roll-up present for a money-viewer, omitted otherwise (P9)")
    void moneyGate() {
        stubScheduleWithOneCategoryBar(2, 10, /* moneyViewer */ true);
        stubEstimate(new BigDecimal("100"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID))
                .thenReturn(List.<Object[]>of(bucketRow(WorkReportStatus.APPROVED, new BigDecimal("40"))));
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualView view = service.getPlanActual(PROJECT_ID);
        assertThat(view.money()).isNotNull();
        assertThat(view.money().currency()).isEqualTo("PLN");
        assertThat(view.money().plannedValue()).isEqualByComparingTo("1000"); // 100 × 10
        assertThat(view.money().confirmedValue()).isEqualByComparingTo("400"); // 40 × 10
    }

    @Test
    @DisplayName("non-money-viewer gets no money block")
    void noMoneyForNonViewer() {
        stubScheduleWithOneCategoryBar(2, 10, /* moneyViewer */ false);
        stubEstimate(new BigDecimal("100"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID)).thenReturn(List.of());
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        assertThat(service.getPlanActual(PROJECT_ID).money()).isNull();
    }

    @Test
    @DisplayName("the assembler is read-only: it persists nothing and bumps no version (P1)")
    void readOnly() {
        stubScheduleWithOneCategoryBar(2, 10, false);
        stubEstimate(new BigDecimal("100"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID)).thenReturn(List.of());
        when(workReportDao.findReportRefsByProject(PROJECT_ID)).thenReturn(List.of());

        PlanActualView view = service.getPlanActual(PROJECT_ID);

        // The read echoes the schedule version unchanged and the DAOs see no save/delete.
        assertThat(view.version()).isEqualTo(5L);
        verify(estimateLineDao, never()).save(org.mockito.ArgumentMatchers.any());
        verify(workReportDao, never()).save(org.mockito.ArgumentMatchers.any());
        verify(projectScheduleService, never()).saveBars(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a WORKER caller's drill-down uses the own-records ref query (R3.4)")
    void workerOwnRecordsScope() {
        authenticate("555", "ROLE_WORKER");
        stubScheduleWithOneCategoryBar(2, 10, false);
        stubEstimate(new BigDecimal("100"), new BigDecimal("10"));
        when(workReportDao.sumReportedQuantityByWorkItemRoomAndStatus(PROJECT_ID)).thenReturn(List.of());
        when(workReportDao.findOwnReportRefsByProject(PROJECT_ID, 555L)).thenReturn(List.of());

        service.getPlanActual(PROJECT_ID);

        verify(workReportDao).findOwnReportRefsByProject(PROJECT_ID, 555L);
        verify(workReportDao, never()).findReportRefsByProject(PROJECT_ID);
    }

    // ---- stubs / builders --------------------------------------------------

    private void stubScheduleWithOneCategoryBar(int startHalfDays, int durationHalfDays, boolean moneyViewer) {
        ScheduleBarView bar = new ScheduleBarView(11L, "Anna",
                startHalfDays / 2.0, durationHalfDays / 2.0, (startHalfDays + durationHalfDays - 1) / 2.0, false);
        ScheduleView view = new ScheduleView(
                PROJECT_ID, LocalDate.now().minusDays(30), null, false, 5L, 1,
                moneyViewer ? "PLN" : null, bar.finishDay(), null, false,
                List.of(), List.of(row(List.of(bar))));
        when(projectScheduleService.getView(PROJECT_ID)).thenReturn(view);
    }

    private void stubScheduleNoAnchor(int startHalfDays, int durationHalfDays) {
        ScheduleBarView bar = new ScheduleBarView(11L, "Anna",
                startHalfDays / 2.0, durationHalfDays / 2.0, (startHalfDays + durationHalfDays - 1) / 2.0, false);
        ScheduleView view = new ScheduleView(
                PROJECT_ID, null, null, false, 5L, 1, null, bar.finishDay(), null, false,
                List.of(), List.of(row(List.of(bar))));
        when(projectScheduleService.getView(PROJECT_ID)).thenReturn(view);
    }

    private static ScheduleRowView row(List<ScheduleBarView> bars) {
        return new ScheduleRowView(CATEGORY_ID, "CAT", 1, "Malowanie", 1, null, null, true, bars, List.of());
    }

    /** Stubs the project's estimate lines with one line → one room-qty cell carrying {@code planned}. */
    private void stubEstimate(BigDecimal planned, BigDecimal unitPrice) {
        EstimateLineEntity line = estimateLine(unitPrice);
        EstimateLineRoomQtyEntity rq = new EstimateLineRoomQtyEntity();
        rq.setLine(line);
        rq.setRoom(room());
        rq.setQuantity(planned);
        line.getRoomQtys().add(rq);
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID)).thenReturn(List.of(line));
    }

    /** Stubs an estimate line with NO room-qty cell (so the reported cell has no plan). */
    private void stubEstimateNoPlan(BigDecimal unitPrice) {
        EstimateLineEntity line = estimateLine(unitPrice);
        EstimateLineRoomQtyEntity rq = new EstimateLineRoomQtyEntity();
        rq.setLine(line);
        rq.setRoom(room());
        rq.setQuantity(BigDecimal.ZERO); // zero plan ⇒ treated as noPlan
        line.getRoomQtys().add(rq);
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID)).thenReturn(List.of(line));
    }

    private static EstimateLineEntity estimateLine(BigDecimal unitPrice) {
        WorkCategoryEntity cat = new WorkCategoryEntity();
        cat.setId(CATEGORY_ID);
        cat.setCode("CAT");
        cat.setNameRU("Малярка");
        cat.setNamePL("Malowanie");

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setId(100L);
        unit.setCode("m2");
        unit.setNameRU("м2");
        unit.setNamePL("m2");

        WorkItemEntity wi = new WorkItemEntity();
        wi.setId(WORK_ITEM_ID);
        wi.setWorkCategory(cat);
        wi.setUnit(unit);
        wi.setNameRU("Грунтовка");
        wi.setNamePL("Gruntowanie");
        wi.setCode("WI-7");

        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(500L);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(1L);
        line.setEstimate(estimate);
        line.setWorkItem(wi);
        line.setUnit(unit);
        line.setUnitPrice(unitPrice);
        line.setQuantity(BigDecimal.ONE);
        line.setRoomQtys(new ArrayList<>());
        return line;
    }

    private static RoomEntity room() {
        RoomEntity room = new RoomEntity();
        room.setId(ROOM_ID);
        room.setLabel("Salon");
        return room;
    }

    private static Object[] bucketRow(WorkReportStatus status, BigDecimal qty) {
        return new Object[] {WORK_ITEM_ID, ROOM_ID, status, qty};
    }

    private static ProjectEntity project(ProjectStatus status) {
        ProjectEntity p = new ProjectEntity();
        p.setId(PROJECT_ID);
        p.setName("Project");
        p.setStatus(status);
        return p;
    }

    private static void authenticate(String principal, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
