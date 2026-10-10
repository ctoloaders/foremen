package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.ScheduleProperties;
import com.foremen.controller.model.schedule.AutoCreateRequest;
import com.foremen.controller.model.schedule.BarEntry;
import com.foremen.controller.model.schedule.SaveBarsRequest;
import com.foremen.controller.model.schedule.ScheduleView;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ProjectScheduleDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.permission.ForemenPermissionEvaluator;

import jakarta.persistence.LockModeType;

/**
 * Plain Mockito unit tests for the {@link ProjectScheduleService} write path (FOR-05-10 task 6.5),
 * the sibling of {@code ProjectScheduleServiceTest} (which covers read / masking / lock / check
 * order). These exercise {@link ProjectScheduleService#saveBars} and
 * {@link ProjectScheduleService#autoCreate} with the DAOs, config, access cache, permission
 * evaluator, and {@code EntityManager} mocked, driving the caller role / admin flag through
 * {@link SecurityContextHolder} exactly as the sibling class does.
 *
 * <p>Design Properties 7 (orphans never surface / are purged on every changing write) and 8 (version
 * monotonicity: each changing write bumps the version exactly once; rejected / no-op writes leave it
 * unchanged) are validated through the observable effects on the mocked collaborators: the forced
 * {@code OPTIMISTIC_FORCE_INCREMENT} lock on the parent schedule (version bump), the
 * {@code auditLogDao.save} invocation (one UPDATE row per changing write, none for no-op), and the
 * mutated owned bar collection (set / clear / unlisted-unchanged, orphan purge, contiguous
 * auto-layout).
 *
 * <p>Covered requirements: 4.6 (orphan purge on every changing write), 7.1 (set / clear / unlisted
 * unchanged, one version increment), 7.5 (no-op save: no version bump, no audit), 8.3 (auto-create
 * contiguity across weekends with no gaps), 9.3 (no man-days / rate in any response or audit
 * snapshot), 10.5 (locked project throws before any mutation — no purge, no version bump),
 * 13.1 / 13.3 / 13.4 (audit row content, empty before-snapshot on the first write, none for
 * rejected / no-op writes).
 */
@ExtendWith(MockitoExtension.class)
class ProjectScheduleServiceWriteTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long SCHEDULE_ID = 900L;
    private static final BigDecimal RATE = new BigDecimal("2000");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ProjectScheduleDao projectScheduleDao;
    @Mock
    private ProjectDao projectDao;
    @Mock
    private EstimateLineDao estimateLineDao;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private ScheduleProperties scheduleProperties;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private ForemenPermissionEvaluator permissionEvaluator;
    @Mock
    private com.foremen.service.model.mapper.ProjectScheduleServiceMapper serviceMapper;
    @Mock
    private com.foremen.service.audit.AuditLogDao auditLogDao;
    @Mock
    private jakarta.persistence.EntityManager entityManager;

    private ProjectScheduleService service;

    @BeforeEach
    void setUp() {
        service = new ProjectScheduleService(
                projectScheduleDao,
                projectDao,
                estimateLineDao,
                projectMemberDao,
                scheduleProperties,
                projectAccessCache,
                permissionEvaluator,
                serviceMapper,
                auditLogDao,
                entityManager);
        // ADMIN caller so writes reach the body without an access rejection; the getView at the end
        // of each write needs the money/edit flags, which an ADMIN resolves through the evaluator.
        authenticate("1", "ROLE_ADMIN");
        lenient().when(scheduleProperties.dailyOutputPerWorker()).thenReturn(RATE);
        lenient().when(permissionEvaluator.isAllowed(eq("ADMIN"), anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    // ---- test-data builders (mirroring ProjectScheduleServiceTest) ---------

    private static WorkCategoryEntity category(Long id, String code, int orderNo) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(id);
        c.setCode(code);
        c.setOrderNo(orderNo);
        c.setNameRU("кат " + code);
        c.setNamePL("kat " + code);
        c.setActive(true);
        return c;
    }

    private static MeasurementUnitEntity unit() {
        MeasurementUnitEntity u = new MeasurementUnitEntity();
        u.setId(100L);
        u.setCode("m2");
        u.setNameRU("м2");
        u.setNamePL("m2");
        return u;
    }

    private static EstimateEntity estimate() {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setId(1L);
        currency.setCode("PLN");
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(500L);
        estimate.setCurrency(currency);
        return estimate;
    }

    private static EstimateLineEntity line(Long id, WorkCategoryEntity cat, BigDecimal valueNet, EstimateEntity estimate) {
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(id + 1000);
        workItem.setWorkCategory(cat);
        workItem.setUnit(unit());
        workItem.setNameRU("работа " + id);
        workItem.setNamePL("praca " + id);
        workItem.setCode("WI-" + id);

        EstimateLineEntity ln = new EstimateLineEntity();
        ln.setId(id);
        ln.setEstimate(estimate);
        ln.setWorkItem(workItem);
        ln.setUnit(unit());
        ln.setLineNo(1);
        ln.setQuantity(BigDecimal.ONE);
        ln.setValueNet(valueNet);
        return ln;
    }

    private static ProjectEntity project(ProjectStatus status) {
        ProjectEntity p = new ProjectEntity();
        p.setId(PROJECT_ID);
        p.setName("Project");
        p.setStatus(status);
        return p;
    }

    private static ProjectScheduleEntity emptySchedule(long version) {
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setId(SCHEDULE_ID);
        schedule.setVersion(version);
        schedule.setBars(new ArrayList<>());
        return schedule;
    }

    private static ProjectScheduleBarEntity bar(Long id, ProjectScheduleEntity schedule,
                                                WorkCategoryEntity cat, int startDay, int durationDays) {
        ProjectScheduleBarEntity b = new ProjectScheduleBarEntity();
        b.setId(id);
        b.setSchedule(schedule);
        b.setWorkCategory(cat);
        b.setStartDay(startDay);
        b.setDurationDays(durationDays);
        return b;
    }

    private static void authenticate(String principal, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Stubs the project + estimate lines + crew + stored schedule used by both the write and the trailing getView. */
    private void stubWrite(ProjectStatus status, List<EstimateLineEntity> lines, int crewSize,
                           ProjectScheduleEntity schedule) {
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project(status)));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID)).thenReturn(lines);
        when(projectScheduleDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.ofNullable(schedule));
        lenient().when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                eq(PROJECT_ID), anyString(), eq(AssignmentStatus.ACTIVE))).thenReturn((long) crewSize);
    }

    /** Captures and parses the single audit row written by a changing write. */
    private AuditLogEntity captureAudit() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao).save(captor.capture());
        return captor.getValue();
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Asserts a snapshot is {@code {"bars":[{workCategoryCode,startDay,durationDays}]}} with no money/man-days/rate. */
    private static void assertSnapshotShape(String json) {
        JsonNode root = parse(json);
        assertThat(root.fieldNames()).toIterable().containsExactly("bars");
        for (JsonNode barNode : root.get("bars")) {
            assertThat(barNode.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("workCategoryCode", "startDay", "durationDays");
            // No man-days, rate, value, or currency ever leaks into a snapshot (R9.3, R13.4).
            assertThat(barNode.has("manDays")).isFalse();
            assertThat(barNode.has("rate")).isFalse();
            assertThat(barNode.has("categoryValue")).isFalse();
            assertThat(barNode.has("value")).isFalse();
            assertThat(barNode.has("currency")).isFalse();
        }
    }

    // =======================================================================
    // saveBars: set / clear / unlisted-unchanged (R7.1) + version bump (Property 8)
    // =======================================================================

    @Nested
    @DisplayName("saveBars set / clear / unlisted-unchanged (R7.1) and single version bump (Property 8)")
    class SaveBarsMutation {

        @Test
        @DisplayName("sets a new bar, clears another, and leaves an unlisted bar unchanged, bumping the version once")
        void setClearAndLeaveUnlisted() {
            EstimateEntity est = estimate();
            WorkCategoryEntity c1 = category(1L, "01", 1); // will be newly scheduled
            WorkCategoryEntity c2 = category(2L, "02", 2); // will be cleared
            WorkCategoryEntity c3 = category(3L, "03", 3); // unlisted -> unchanged
            List<EstimateLineEntity> lines = List.of(
                    line(10L, c1, new BigDecimal("100"), est),
                    line(20L, c2, new BigDecimal("200"), est),
                    line(30L, c3, new BigDecimal("300"), est));

            ProjectScheduleEntity schedule = emptySchedule(5L);
            schedule.getBars().add(bar(201L, schedule, c2, 3, 4));  // c2 scheduled, will be cleared
            schedule.getBars().add(bar(301L, schedule, c3, 10, 2)); // c3 scheduled, left unlisted
            stubWrite(ProjectStatus.DRAFT, lines, 2, schedule);

            SaveBarsRequest request = new SaveBarsRequest(5L, List.of(
                    new BarEntry(1L, 2, 3),       // set c1 bar (new)
                    new BarEntry(2L, null, null))); // clear c2 bar

            ScheduleView view = service.saveBars(PROJECT_ID, request);

            // c1 now scheduled, c2 cleared, c3 untouched.
            assertThat(view.rows()).hasSize(3);
            assertThat(rowOf(view, 1L).startDay()).isEqualTo(2);
            assertThat(rowOf(view, 1L).durationDays()).isEqualTo(3);
            assertThat(rowOf(view, 2L).startDay()).isNull();
            assertThat(rowOf(view, 2L).durationDays()).isNull();
            assertThat(rowOf(view, 3L).startDay()).isEqualTo(10);
            assertThat(rowOf(view, 3L).durationDays()).isEqualTo(2);

            // Exactly one forced version increment on the parent schedule (Property 8).
            verify(entityManager).lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            verify(entityManager).flush();
            // One audit row written for the changing write.
            verify(auditLogDao, times(1)).save(any());
        }
    }

    // =======================================================================
    // saveBars no-op (R7.5): no version bump, no audit (Property 8)
    // =======================================================================

    @Test
    @DisplayName("a save equal to the stored state is a no-op: no version bump, no audit row (R7.5, Property 8)")
    void noOpSaveDoesNotBumpOrAudit() {
        EstimateEntity est = estimate();
        WorkCategoryEntity c1 = category(1L, "01", 1);
        List<EstimateLineEntity> lines = List.of(line(10L, c1, new BigDecimal("100"), est));

        ProjectScheduleEntity schedule = emptySchedule(5L);
        schedule.getBars().add(bar(101L, schedule, c1, 1, 4));
        stubWrite(ProjectStatus.DRAFT, lines, 2, schedule);

        // Entry equals the stored bar exactly.
        SaveBarsRequest request = new SaveBarsRequest(5L, List.of(new BarEntry(1L, 1, 4)));

        ScheduleView view = service.saveBars(PROJECT_ID, request);

        assertThat(view.version()).isEqualTo(5L);
        assertThat(rowOf(view, 1L).startDay()).isEqualTo(1);
        assertThat(rowOf(view, 1L).durationDays()).isEqualTo(4);

        // No version bump and no audit for a no-op (Property 8, R7.5).
        verify(entityManager, never()).lock(any(), any());
        verify(entityManager, never()).flush();
        verify(auditLogDao, never()).save(any());
    }

    // =======================================================================
    // Orphan purge on every changing write (R4.6, Property 7)
    // =======================================================================

    @Test
    @DisplayName("a changing save purges orphan bars (category no longer a row) in the same transaction (R4.6, Property 7)")
    void changingSavePurgesOrphans() {
        EstimateEntity est = estimate();
        WorkCategoryEntity c1 = category(1L, "01", 1);        // current row
        WorkCategoryEntity orphan = category(99L, "99", 9);   // no estimate line -> orphan
        List<EstimateLineEntity> lines = List.of(line(10L, c1, new BigDecimal("100"), est));

        ProjectScheduleEntity schedule = emptySchedule(5L);
        schedule.getBars().add(bar(101L, schedule, c1, 1, 2));         // current-row bar
        schedule.getBars().add(bar(991L, schedule, orphan, 5, 3));     // orphan bar
        stubWrite(ProjectStatus.DRAFT, lines, 2, schedule);

        // Change the current-row bar so the write is not a no-op; the orphan must be purged too.
        SaveBarsRequest request = new SaveBarsRequest(5L, List.of(new BarEntry(1L, 2, 3)));

        service.saveBars(PROJECT_ID, request);

        // The owned collection no longer holds the orphan bar.
        assertThat(schedule.getBars())
                .extracting(b -> b.getWorkCategory().getId())
                .containsExactly(1L);
        verify(entityManager).lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }

    // =======================================================================
    // No orphan purge / version bump on a locked project (R10.5, Property 7/8)
    // =======================================================================

    @Test
    @DisplayName("a locked project throws before any mutation: no purge, no version bump, no audit (R10.5)")
    void lockedProjectThrowsBeforeMutation() {
        WorkCategoryEntity c1 = category(1L, "01", 1);
        WorkCategoryEntity orphan = category(99L, "99", 9);

        ProjectScheduleEntity schedule = emptySchedule(5L);
        schedule.getBars().add(bar(101L, schedule, c1, 1, 2));
        schedule.getBars().add(bar(991L, schedule, orphan, 5, 3)); // orphan would be purged if editable

        // ACTIVE is a Schedule_Locked_Status; assertEditable throws 409 before any bar is touched.
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project(ProjectStatus.ACTIVE)));

        SaveBarsRequest request = new SaveBarsRequest(5L, List.of(new BarEntry(1L, 2, 3)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveBars(PROJECT_ID, request))
                .isInstanceOf(com.foremen.exception.ForemenApiException.class)
                .satisfies(ex -> assertThat(
                        ((com.foremen.exception.ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.schedule.locked"));

        // The orphan bar is still present — the lock throws before purgeOrphans runs (R10.5, Property 7).
        assertThat(schedule.getBars()).hasSize(2);
        // No version bump and no audit on a rejected write (Property 8, R13.4).
        verify(entityManager, never()).lock(any(), any());
        verify(entityManager, never()).flush();
        verify(auditLogDao, never()).save(any());
    }

    // =======================================================================
    // Rejected (invalid) save: no mutation, no version bump, no audit (R13.4, Property 8)
    // =======================================================================

    @Test
    @DisplayName("an invalid bar entry is rejected before mutation: no version bump, no audit (R13.4, Property 8)")
    void invalidEntryRejectedWithoutMutation() {
        EstimateEntity est = estimate();
        WorkCategoryEntity c1 = category(1L, "01", 1);
        List<EstimateLineEntity> lines = List.of(line(10L, c1, new BigDecimal("100"), est));

        ProjectScheduleEntity schedule = emptySchedule(5L);
        schedule.getBars().add(bar(101L, schedule, c1, 1, 2));
        stubWrite(ProjectStatus.DRAFT, lines, 2, schedule);

        // Half-null entry (startDay without durationDays) is invalid (R7.3).
        SaveBarsRequest request = new SaveBarsRequest(5L, List.of(new BarEntry(1L, 5, null)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveBars(PROJECT_ID, request))
                .isInstanceOf(com.foremen.exception.ForemenApiException.class)
                .satisfies(ex -> assertThat(
                        ((com.foremen.exception.ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.schedule.bar.invalid"));

        // The stored bar is untouched; nothing is persisted or audited.
        assertThat(schedule.getBars()).singleElement()
                .satisfies(b -> {
                    assertThat(b.getStartDay()).isEqualTo(1);
                    assertThat(b.getDurationDays()).isEqualTo(2);
                });
        verify(entityManager, never()).lock(any(), any());
        verify(auditLogDao, never()).save(any());
    }

    // =======================================================================
    // Audit row content for a first changing save (R13.1, R13.3, R13.4)
    // =======================================================================

    @Test
    @DisplayName("a first save writes one UPDATE/SAVE_BARS audit row with an empty before and the after bars (R13.1, R13.3, R13.4)")
    void firstSaveAuditContent() {
        EstimateEntity est = estimate();
        WorkCategoryEntity c1 = category(1L, "01", 1);
        List<EstimateLineEntity> lines = List.of(line(10L, c1, new BigDecimal("100"), est));

        // No schedule yet -> first write. loadOrCreate makes a new schedule.
        stubWrite(ProjectStatus.DRAFT, lines, 2, null);

        SaveBarsRequest request = new SaveBarsRequest(0L, List.of(new BarEntry(1L, 1, 3)));

        service.saveBars(PROJECT_ID, request);

        AuditLogEntity audit = captureAudit();
        assertThat(audit.getEntityClass()).isEqualTo("ProjectScheduleEntity");
        assertThat(audit.getOperation()).isEqualTo("SAVE_BARS");
        assertThat(audit.getPerformedBy()).isEqualTo("1");
        // Empty before-snapshot on the first write (R13.3).
        assertThat(parse(audit.getSnapshotBefore()).get("bars")).isEmpty();
        assertSnapshotShape(audit.getSnapshotBefore());
        // After-snapshot carries the single saved bar by category CODE (never id), no money/man-days (R13.4).
        assertSnapshotShape(audit.getSnapshotAfter());
        JsonNode afterBars = parse(audit.getSnapshotAfter()).get("bars");
        assertThat(afterBars).hasSize(1);
        assertThat(afterBars.get(0).get("workCategoryCode").asText()).isEqualTo("01");
        assertThat(afterBars.get(0).get("startDay").asInt()).isEqualTo(1);
        assertThat(afterBars.get(0).get("durationDays").asInt()).isEqualTo(3);
    }

    // =======================================================================
    // autoCreate: contiguity across weekends, version bump, audit (R8.3, Property 8, R13)
    // =======================================================================

    @Nested
    @DisplayName("autoCreate contiguity and version/audit (R8.3, Property 8, R13)")
    class AutoCreate {

        @Test
        @DisplayName("lays bars contiguously from day 1 with no weekend/holiday gaps and bumps the version once")
        void contiguousLayoutSpanningWeekends() {
            EstimateEntity est = estimate();
            // crew = 1, rate = 2000. durations: c1 = ceil(16000/2000)=8, c2 = ceil(4000/2000)=2,
            // c3 = ceil(10000/2000)=5. Contiguous layout spans multiple weekends: day 1..8, 9..10, 11..15.
            WorkCategoryEntity c1 = category(1L, "01", 1);
            WorkCategoryEntity c2 = category(2L, "02", 2);
            WorkCategoryEntity c3 = category(3L, "03", 3);
            List<EstimateLineEntity> lines = List.of(
                    line(10L, c1, new BigDecimal("16000"), est),
                    line(20L, c2, new BigDecimal("4000"), est),
                    line(30L, c3, new BigDecimal("10000"), est));

            ProjectScheduleEntity schedule = emptySchedule(2L);
            stubWrite(ProjectStatus.DRAFT, lines, 1, schedule);

            ScheduleView view = service.autoCreate(PROJECT_ID, new AutoCreateRequest(2L));

            // Contiguity (R8.3, Property 8/contiguity): start(1)=1; start(k+1)=finish(k)+1; no gaps.
            assertThat(rowOf(view, 1L).startDay()).isEqualTo(1);
            assertThat(rowOf(view, 1L).durationDays()).isEqualTo(8);
            assertThat(rowOf(view, 1L).finishDay()).isEqualTo(8);
            assertThat(rowOf(view, 2L).startDay()).isEqualTo(9);
            assertThat(rowOf(view, 2L).durationDays()).isEqualTo(2);
            assertThat(rowOf(view, 2L).finishDay()).isEqualTo(10);
            assertThat(rowOf(view, 3L).startDay()).isEqualTo(11);
            assertThat(rowOf(view, 3L).durationDays()).isEqualTo(5);
            assertThat(rowOf(view, 3L).finishDay()).isEqualTo(15);
            // Schedule finish = Σ durations.
            assertThat(view.finishDay()).isEqualTo(15);

            // Exactly one forced version increment (Property 8).
            verify(entityManager).lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            verify(entityManager).flush();
            // One AUTO_CREATE audit row, correct shape, no money/man-days/rate (R13.1, R13.4).
            AuditLogEntity audit = captureAudit();
            assertThat(audit.getOperation()).isEqualTo("AUTO_CREATE");
            assertThat(audit.getEntityClass()).isEqualTo("ProjectScheduleEntity");
            assertSnapshotShape(audit.getSnapshotBefore());
            assertSnapshotShape(audit.getSnapshotAfter());
            JsonNode afterBars = parse(audit.getSnapshotAfter()).get("bars");
            assertThat(afterBars).hasSize(3);
            assertThat(afterBars.get(0).get("workCategoryCode").asText()).isEqualTo("01");
        }

        @Test
        @DisplayName("replaces all existing bars (including a stale/orphan bar) and purges them (R8.5, Property 7)")
        void replacesAllBarsAndPurgesOrphans() {
            EstimateEntity est = estimate();
            WorkCategoryEntity c1 = category(1L, "01", 1);
            WorkCategoryEntity orphan = category(99L, "99", 9);
            List<EstimateLineEntity> lines = List.of(line(10L, c1, new BigDecimal("4000"), est));

            ProjectScheduleEntity schedule = emptySchedule(2L);
            schedule.getBars().add(bar(101L, schedule, c1, 50, 1));    // stale bar for current row
            schedule.getBars().add(bar(991L, schedule, orphan, 5, 3)); // orphan bar
            stubWrite(ProjectStatus.DRAFT, lines, 1, schedule);

            service.autoCreate(PROJECT_ID, new AutoCreateRequest(2L));

            // After replace: only the current-row bar remains, laid out from day 1 (orphan purged).
            assertThat(schedule.getBars())
                    .extracting(b -> b.getWorkCategory().getId())
                    .containsExactly(1L);
            assertThat(schedule.getBars()).singleElement().satisfies(b -> {
                assertThat(b.getStartDay()).isEqualTo(1);
                assertThat(b.getDurationDays()).isEqualTo(2); // ceil(4000/2000)
            });
            verify(entityManager).lock(schedule, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
    }

    // =======================================================================
    // No man-days / rate in any write response (R9.3)
    // =======================================================================

    @Test
    @DisplayName("no save or auto-create response carries man-days or the Daily_Output_Rate (R9.3)")
    void noManDaysOrRateInResponses() {
        EstimateEntity est = estimate();
        WorkCategoryEntity c1 = category(1L, "01", 1);
        List<EstimateLineEntity> lines = List.of(line(10L, c1, new BigDecimal("4000"), est));

        ProjectScheduleEntity schedule = emptySchedule(5L);
        stubWrite(ProjectStatus.DRAFT, lines, 2, schedule);

        ScheduleView view = service.saveBars(PROJECT_ID, new SaveBarsRequest(5L, List.of(new BarEntry(1L, 1, 2))));

        // Serialize the whole response and assert no rate / man-days token appears anywhere (R9.3).
        String json;
        try {
            json = MAPPER.writeValueAsString(view);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(json).doesNotContain("manDays").doesNotContain("rate")
                .doesNotContain("dailyOutput").doesNotContain("2000");
        // The row exposes only duration-related integer fields, never a man-days value.
        assertThat(rowOf(view, 1L).durationDays()).isEqualTo(2);
    }

    // ---- helpers -----------------------------------------------------------

    private static com.foremen.controller.model.schedule.ScheduleRowView rowOf(ScheduleView view, Long categoryId) {
        return view.rows().stream()
                .filter(r -> categoryId.equals(r.workCategoryId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for category " + categoryId));
    }
}
