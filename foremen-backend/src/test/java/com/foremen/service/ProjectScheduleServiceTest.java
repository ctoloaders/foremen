package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.config.ScheduleProperties;
import com.foremen.controller.model.schedule.ScheduleRowView;
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
import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.schedule.ScheduleCalculator;

/**
 * Plain Mockito unit tests for {@link ProjectScheduleService} (FOR-05-10 task 5.4), covering the
 * read, money-masking, locale-fallback, lifecycle-lock, and check-order behavior established by
 * tasks 5.1–5.3. The DAOs, config, access cache, and permission evaluator are mocked; the caller
 * role / admin flag is driven through {@link SecurityContextHolder} and the request language
 * through {@link LocaleContextHolder}, mirroring {@code ProjectMemberServiceTest}.
 *
 * <p>Only the methods present at this stage are exercised — {@link ProjectScheduleService#getView},
 * {@link ProjectScheduleService#loadAccessibleProject}, {@link ProjectScheduleService#assertEditable},
 * and {@link ProjectScheduleService#assertVersion}. The writes ({@code saveBars} / {@code autoCreate})
 * and readiness arrive in task 6.x.
 *
 * <p>Covered requirements: 3.5 (check order), 4.5 (orphan-bar exclusion), 5.3 (money masking),
 * 5.6 (no-schedule read is version 0 and persists nothing), 5.7 (ru → pl → code name fallback),
 * 10.2 / 10.3 (writes locked outside an editable status, for ADMIN too; reads stay allowed).
 */
@ExtendWith(MockitoExtension.class)
class ProjectScheduleServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final BigDecimal RATE = new BigDecimal("2000");

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
        // Default to an ADMIN caller so getView() reaches the body without an access rejection;
        // tests that need a non-ADMIN role re-authenticate explicitly.
        authenticate("1", "ROLE_ADMIN");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    // ---- test-data builders ------------------------------------------------

    private static WorkCategoryEntity category(Long id, String code, int orderNo, String nameRU, String namePL) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(id);
        c.setCode(code);
        c.setOrderNo(orderNo);
        c.setNameRU(nameRU);
        c.setNamePL(namePL);
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

    private static EstimateEntity estimateWithCurrency(String currencyCode) {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setId(1L);
        currency.setCode(currencyCode);
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(500L);
        estimate.setCurrency(currency);
        return estimate;
    }

    /** An estimate line in the given category with the given net value, wired to an estimate + unit. */
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

    private static ProjectScheduleEntity scheduleWithBar(long version, WorkCategoryEntity cat, int startDay, int durationDays) {
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setId(900L);
        schedule.setVersion(version);
        ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
        bar.setId(901L);
        bar.setSchedule(schedule);
        bar.setWorkCategory(cat);
        bar.setStartDay(startDay);
        bar.setDurationDays(durationDays);
        schedule.getBars().add(bar);
        return schedule;
    }

    private static void authenticate(String principal, String authority) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Stubs an editable DRAFT project with the given estimate lines and crew size for a getView run. */
    private void stubGetView(ProjectStatus status, List<EstimateLineEntity> lines, int crewSize,
                             ProjectScheduleEntity schedule) {
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project(status)));
        when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID)).thenReturn(lines);
        when(projectScheduleDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.ofNullable(schedule));
        lenient().when(projectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus(
                eq(PROJECT_ID), anyString(), eq(AssignmentStatus.ACTIVE))).thenReturn((long) crewSize);
        lenient().when(scheduleProperties.dailyOutputPerWorker()).thenReturn(RATE);
    }

    // =======================================================================
    // Money masking (R5.3)
    // =======================================================================

    @Nested
    @DisplayName("getView money masking (R5.3)")
    class MoneyMasking {

        @Test
        @DisplayName("a Money_Viewer sees the currency and every row's categoryValue")
        void moneyViewerSeesCurrencyAndValues() {
            // ADMIN is always a Money_Viewer (ForemenPermissionEvaluator ADMIN bypass).
            EstimateEntity estimate = estimateWithCurrency("PLN");
            WorkCategoryEntity cat = category(1L, "01", 1, "Демонтаж", "Rozbiórka");
            EstimateLineEntity ln = line(10L, cat, new BigDecimal("6773.80"), estimate);
            stubGetView(ProjectStatus.DRAFT, List.of(ln), 2, null);
            when(permissionEvaluator.isAllowed("ADMIN", "ESTIMATE", "READ")).thenReturn(true);

            ScheduleView view = service.getView(PROJECT_ID);

            assertThat(view.currency()).isEqualTo("PLN");
            assertThat(view.rows()).singleElement()
                    .satisfies(row -> assertThat(row.categoryValue()).isEqualByComparingTo("6773.80"));
        }

        @Test
        @DisplayName("a non-viewer gets a null currency and null categoryValue (omitted from the payload)")
        void nonViewerGetsNoMoney() {
            // A WORKER role lacks ESTIMATE READ (changeset 135), so it is not a Money_Viewer.
            authenticate("7", "ROLE_WORKER");
            when(projectAccessCache.get(7L)).thenReturn(Set.of(PROJECT_ID));
            EstimateEntity estimate = estimateWithCurrency("PLN");
            WorkCategoryEntity cat = category(1L, "01", 1, "Демонтаж", "Rozbiórka");
            EstimateLineEntity ln = line(10L, cat, new BigDecimal("6773.80"), estimate);
            stubGetView(ProjectStatus.DRAFT, List.of(ln), 2, null);
            when(permissionEvaluator.isAllowed("WORKER", "ESTIMATE", "READ")).thenReturn(false);
            // A non-ADMIN also does not hold WORK_SCHEDULE UPDATE here.
            lenient().when(permissionEvaluator.isAllowed("WORKER", "WORK_SCHEDULE", "UPDATE")).thenReturn(false);

            ScheduleView view = service.getView(PROJECT_ID);

            assertThat(view.currency()).isNull();
            assertThat(view.rows()).singleElement()
                    .satisfies(row -> assertThat(row.categoryValue()).isNull());
        }
    }

    // =======================================================================
    // No-schedule read (R5.6)
    // =======================================================================

    @Test
    @DisplayName("a project with no schedule reads version 0, every row unscheduled, and persists nothing (R5.6)")
    void noScheduleReadIsVersionZeroAndUnscheduled() {
        EstimateEntity estimate = estimateWithCurrency("PLN");
        WorkCategoryEntity cat = category(1L, "01", 1, "Демонтаж", "Rozbiórka");
        EstimateLineEntity ln = line(10L, cat, new BigDecimal("100"), estimate);
        stubGetView(ProjectStatus.DRAFT, List.of(ln), 1, null); // no schedule row
        when(permissionEvaluator.isAllowed("ADMIN", "ESTIMATE", "READ")).thenReturn(true);

        ScheduleView view = service.getView(PROJECT_ID);

        assertThat(view.version()).isZero();
        assertThat(view.finishDay()).isNull();
        assertThat(view.finishDate()).isNull();
        assertThat(view.rows()).singleElement().satisfies(row -> {
            assertThat(row.startDay()).isNull();
            assertThat(row.durationDays()).isNull();
            assertThat(row.finishDay()).isNull();
        });
        // The read never creates a project_schedules row.
        verify(projectScheduleDao, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // =======================================================================
    // Orphan-bar exclusion (R4.5)
    // =======================================================================

    @Test
    @DisplayName("a bar whose category is no longer an estimate row is excluded from the view (R4.5)")
    void orphanBarIsExcluded() {
        EstimateEntity estimate = estimateWithCurrency("PLN");
        WorkCategoryEntity currentCat = category(1L, "01", 1, "Демонтаж", "Rozbiórka");
        WorkCategoryEntity orphanCat = category(2L, "02", 2, "Снятая", "Usunięta");
        EstimateLineEntity ln = line(10L, currentCat, new BigDecimal("100"), estimate);

        // Stored schedule has a bar for a category (orphanCat) that is NOT a current row.
        ProjectScheduleEntity schedule = scheduleWithBar(3L, orphanCat, 1, 5);
        stubGetView(ProjectStatus.DRAFT, List.of(ln), 1, schedule);
        when(permissionEvaluator.isAllowed("ADMIN", "ESTIMATE", "READ")).thenReturn(true);

        ScheduleView view = service.getView(PROJECT_ID);

        // Only the current row is present and it is unscheduled; the orphan bar surfaces nowhere.
        assertThat(view.rows()).singleElement().satisfies(row -> {
            assertThat(row.workCategoryId()).isEqualTo(1L);
            assertThat(row.startDay()).isNull();
            assertThat(row.durationDays()).isNull();
        });
        // The schedule-wide finish ignores the orphan bar entirely.
        assertThat(view.finishDay()).isNull();
    }

    // =======================================================================
    // Locale fallback (R5.7)
    // =======================================================================

    @Nested
    @DisplayName("getView locale fallback ru → pl → code (R5.7)")
    class LocaleFallback {

        @Test
        @DisplayName("a Russian request returns the Russian name")
        void russianRequestUsesRussianName() {
            LocaleContextHolder.setLocale(new Locale("ru"));
            runNameTest("Демонтаж", "Rozbiórka", "01", "Демонтаж");
        }

        @Test
        @DisplayName("a Polish (default) request returns the Polish name")
        void polishRequestUsesPolishName() {
            LocaleContextHolder.setLocale(new Locale("pl"));
            runNameTest("Демонтаж", "Rozbiórka", "01", "Rozbiórka");
        }

        @Test
        @DisplayName("a Russian request with a blank Russian name falls back to Polish")
        void russianFallsBackToPolishWhenRussianBlank() {
            LocaleContextHolder.setLocale(new Locale("ru"));
            runNameTest("  ", "Rozbiórka", "01", "Rozbiórka");
        }

        @Test
        @DisplayName("both names blank falls back to the category code")
        void bothBlankFallsBackToCode() {
            LocaleContextHolder.setLocale(new Locale("ru"));
            runNameTest("", "", "01", "01");
        }

        private void runNameTest(String nameRU, String namePL, String code, String expected) {
            EstimateEntity estimate = estimateWithCurrency("PLN");
            WorkCategoryEntity cat = category(1L, code, 1, nameRU, namePL);
            EstimateLineEntity ln = line(10L, cat, new BigDecimal("100"), estimate);
            stubGetView(ProjectStatus.DRAFT, List.of(ln), 1, null);
            when(permissionEvaluator.isAllowed("ADMIN", "ESTIMATE", "READ")).thenReturn(true);

            ScheduleView view = service.getView(PROJECT_ID);

            assertThat(view.rows()).singleElement()
                    .satisfies(row -> assertThat(row.name()).isEqualTo(expected));
        }
    }

    // =======================================================================
    // Lifecycle lock (R10.2, R10.3) and locked reads
    // =======================================================================

    @Nested
    @DisplayName("assertEditable lifecycle lock (R10.2, R10.3)")
    class LifecycleLock {

        @Test
        @DisplayName("an editable status (DRAFT) does not throw")
        void editableStatusPasses() {
            service.assertEditable(project(ProjectStatus.DRAFT));
        }

        @Test
        @DisplayName("every Schedule_Locked_Status rejects a write with 409 error.schedule.locked, ADMIN included (R10.3)")
        void lockedStatusesRejectWritesForEveryoneIncludingAdmin() {
            // The caller is ADMIN (default), yet the lock still trips — the gate keys off project
            // status, not the caller's role (R10.3).
            for (ProjectStatus locked : List.of(ProjectStatus.ACTIVE, ProjectStatus.COMPLETED, ProjectStatus.CANCELLED)) {
                assertThatThrownBy(() -> service.assertEditable(project(locked)))
                        .isInstanceOf(ForemenApiException.class)
                        .satisfies(ex -> {
                            ForemenApiException api = (ForemenApiException) ex;
                            assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(api.getMessageCode()).isEqualTo("error.schedule.locked");
                        });
            }
        }

        @Test
        @DisplayName("reading a schedule in a locked status is allowed (getView does not lock)")
        void lockedReadIsAllowed() {
            EstimateEntity estimate = estimateWithCurrency("PLN");
            WorkCategoryEntity cat = category(1L, "01", 1, "Демонтаж", "Rozbiórka");
            EstimateLineEntity ln = line(10L, cat, new BigDecimal("100"), estimate);
            ProjectScheduleEntity schedule = scheduleWithBar(4L, cat, 1, 3);
            // Project is ACTIVE — a Schedule_Locked_Status — yet the read must still succeed.
            stubGetView(ProjectStatus.ACTIVE, List.of(ln), 1, schedule);
            when(permissionEvaluator.isAllowed("ADMIN", "ESTIMATE", "READ")).thenReturn(true);

            ScheduleView view = service.getView(PROJECT_ID);

            assertThat(view.version()).isEqualTo(4L);
            assertThat(view.rows()).singleElement().satisfies(row -> {
                assertThat(row.startDay()).isEqualTo(1);
                assertThat(row.durationDays()).isEqualTo(3);
                assertThat(row.finishDay()).isEqualTo(ScheduleCalculator.finishDay(1, 3));
            });
            // editable is false in a locked status even for an ADMIN with UPDATE.
            assertThat(view.editable()).isFalse();
        }
    }

    // =======================================================================
    // Check-order precedence: access 404 BEFORE lock 409 BEFORE version 409 (R3.5)
    // =======================================================================

    @Nested
    @DisplayName("check-order precedence (R3.5): access 404 → lock 409 → version 409")
    class CheckOrder {

        @Test
        @DisplayName("a non-accessible project is 404 before any lock or version check (non-ADMIN)")
        void accessDeniedIs404ForNonAccessibleProject() {
            authenticate("7", "ROLE_MANAGER");
            when(projectAccessCache.get(7L)).thenReturn(Set.of()); // project not accessible

            assertThatThrownBy(() -> service.loadAccessibleProject(PROJECT_ID))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                    });
            // The access gate short-circuits before even loading the project row.
            verify(projectDao, never()).findById(anyLong());
        }

        @Test
        @DisplayName("a missing project is 404 with the same body as a non-accessible one")
        void missingProjectIs404() {
            // ADMIN caller (default) bypasses the accessibility gate but the project does not exist.
            when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.loadAccessibleProject(PROJECT_ID))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                    });
        }

        @Test
        @DisplayName("a locked status (409 locked) takes precedence over a stale version (409 conflict)")
        void lockBeforeVersion() {
            // Simulate the write flow's ordering: assertEditable runs before assertVersion. On a
            // locked project both would fail, but the lock must be reported, never the version.
            ProjectEntity locked = project(ProjectStatus.ACTIVE);
            ProjectScheduleEntity schedule = scheduleWithBar(5L, category(1L, "01", 1, "a", "b"), 1, 2);

            assertThatThrownBy(() -> {
                service.assertEditable(locked);          // throws here (locked)
                service.assertVersion(schedule, 0L);     // never reached (would be a conflict)
            })
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                            .isEqualTo("error.schedule.locked"));
        }

        @Test
        @DisplayName("on an editable project, a stale version is a 409 conflict")
        void staleVersionIsConflictOnEditableProject() {
            ProjectEntity editable = project(ProjectStatus.DRAFT);
            ProjectScheduleEntity schedule = scheduleWithBar(5L, category(1L, "01", 1, "a", "b"), 1, 2);

            assertThatThrownBy(() -> {
                service.assertEditable(editable);        // passes (editable)
                service.assertVersion(schedule, 0L);     // stale: stored 5 != requested 0
            })
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> {
                        ForemenApiException api = (ForemenApiException) ex;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(api.getMessageCode()).isEqualTo("error.schedule.conflict");
                    });
        }

        @Test
        @DisplayName("a matching version on an editable project passes both gates")
        void matchingVersionPasses() {
            ProjectEntity editable = project(ProjectStatus.DRAFT);
            ProjectScheduleEntity schedule = scheduleWithBar(5L, category(1L, "01", 1, "a", "b"), 1, 2);

            service.assertEditable(editable);
            service.assertVersion(schedule, 5L);
        }

        @Test
        @DisplayName("a first write (no schedule yet) matches version 0")
        void firstWriteMatchesVersionZero() {
            service.assertVersion(null, 0L);
        }
    }
}
