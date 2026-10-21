package com.foremen.service.integration;

import com.foremen.controller.model.schedule.ScheduleBarView;
import com.foremen.controller.model.schedule.ScheduleRowView;
import com.foremen.controller.model.schedule.ScheduleView;
import com.foremen.controller.model.schedule.ScheduleWorkerView;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.ProjectScheduleService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring integration test for FOR-05-10b task 4.1 — the per-worker {@code getView} read model
 * ({@link ProjectScheduleService#getView(Long)}; Requirements 3.1, 3.2, 3.3, 7.2, 7.3). It proves
 * the rewritten read maps each {@code Schedule_Row} to a {@code bars} array (one
 * {@link ScheduleBarView} per {@code Covering_Worker}, plus the virtual uncovered bar), sets the
 * {@code covered} flag, exposes the stored half-day counts as 0.5-multiple day values, and builds
 * the {@code workers} legend from the project's ACTIVE WORKER members.
 *
 * <p>Boot harness mirrors {@link MemberWorkCategoriesIntegrationTest}: {@code @SpringBootTest} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}, where Liquibase is disabled
 * and Hibernate {@code create-drop} builds the schema from the mapped entities (so the per-worker
 * {@code project_schedule_bars.project_member_id} column and the
 * {@code project_member_work_categories} join table come from the entity mappings). The caller is an
 * authenticated ADMIN, so the project-scope gate is bypassed and the money fields are populated; the
 * ADMIN also resolves {@code WORK_SCHEDULE} UPDATE via the permission evaluator so a DRAFT project is
 * {@code editable}.
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run suffix (a {@code runId} +
 * counter), each test uses a fresh {@code projectId}, and {@link #tearDown()} deletes every row this
 * test created (deepest-first in FK order), so the suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3, 7.2, 7.3
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@DisplayName("ProjectScheduleService.getView — per-worker bars (FOR-05-10b task 4.1)")
class ScheduleGetViewPerWorkerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private ProjectScheduleService projectScheduleService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private static final AtomicLong COUNTER = new AtomicLong();
    private final long runId = System.nanoTime();

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        // ADMIN (numeric principal) so the project-scope gate is bypassed and money fields populate.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from ProjectScheduleBarEntity b where b.schedule.project.name like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery(
                            "delete from ProjectScheduleEntity s where s.project.name like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateLineEntity l where l.estimate.project.name like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateEntity e where e.project.name like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            // Members reference the per-run project id; drop their work-category links first.
            entityManager.createNativeQuery(
                            "delete from project_member_work_categories where project_member_id in "
                                    + "(select id from project_members where project_id in "
                                    + "(select id from projects where name like ?1))")
                    .setParameter(1, "%" + runId + "%").executeUpdate();
            entityManager.createNativeQuery(
                            "delete from project_members where project_id in "
                                    + "(select id from projects where name like ?1)")
                    .setParameter(1, "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.code like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity c where c.code like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity u where u.code like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
            entityManager.createQuery("delete from ProjectEntity p where p.name like :p")
                    .setParameter("p", "%" + runId + "%").executeUpdate();
        });
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    // =============================================================================================
    // R3.1 / R7.2 — a category covered by two workers renders one bar per worker, covered = true
    // =============================================================================================

    @Test
    @DisplayName("R3.1/R7.2 — a two-worker category renders one bar per covering worker; covered=true")
    void coveredCategoryRendersOneBarPerWorker() {
        Fixture f = new Fixture();
        long projectId = f.createProjectWithOneCategoryLine(new BigDecimal("1000"));

        // Two ACTIVE WORKER members, both covering the category.
        long memberA = f.addActiveWorker("Jan Kowalski", f.category);
        long memberB = f.addActiveWorker("Piotr Nowak", f.category);

        // A schedule with one bar per worker on the half-day grid: start half-day 2 (day 1.0),
        // duration 5 half-days (2.5 days) for A; start half-day 2, duration 7 half-days (3.5 days) for B.
        f.createSchedule(projectId, bars -> {
            bars.add(f.makeBar(f.category, memberA, 2, 5));
            bars.add(f.makeBar(f.category, memberB, 2, 7));
        });

        ScheduleView view = projectScheduleService.getView(projectId);

        ScheduleRowView row = singleRow(view);
        assertThat(row.covered()).isTrue();
        assertThat(row.workCategoryId()).isEqualTo(f.category);
        assertThat(row.bars()).hasSize(2);

        ScheduleBarView barA = barOfMember(row, memberA);
        assertThat(barA.workerName()).isEqualTo("Jan Kowalski");
        assertThat(barA.startDay()).isEqualTo(1.0);         // 2 / 2
        assertThat(barA.durationDays()).isEqualTo(2.5);     // 5 / 2
        assertThat(barA.finishDay()).isEqualTo(3.0);        // (2 + 5 - 1) / 2

        ScheduleBarView barB = barOfMember(row, memberB);
        assertThat(barB.workerName()).isEqualTo("Piotr Nowak");
        assertThat(barB.startDay()).isEqualTo(1.0);         // 2 / 2
        assertThat(barB.durationDays()).isEqualTo(3.5);     // 7 / 2
        assertThat(barB.finishDay()).isEqualTo(4.0);        // (2 + 7 - 1) / 2

        // The worker legend lists both ACTIVE WORKER members (color computed client-side -> null).
        assertThat(view.workers()).extracting(ScheduleWorkerView::projectMemberId)
                .containsExactlyInAnyOrder(memberA, memberB);
        assertThat(view.workers()).extracting(ScheduleWorkerView::name)
                .containsExactlyInAnyOrder("Jan Kowalski", "Piotr Nowak");
        assertThat(view.workers()).allSatisfy(w -> assertThat(w.color()).isNull());
        assertThat(view.crewSize()).isEqualTo(2);

        // Schedule-wide finish = max bar finish day = 4.0 (half-day 8 -> calendar day 4).
        assertThat(view.finishDay()).isEqualTo(4.0);
    }

    // =============================================================================================
    // R3.2 / R7.3 — an uncovered category: covered = false, a single null-member virtual bar
    // =============================================================================================

    @Test
    @DisplayName("R3.2/R7.3 — an uncovered category renders a single null-member virtual bar; covered=false")
    void uncoveredCategoryRendersVirtualBar() {
        Fixture f = new Fixture();
        long projectId = f.createProjectWithOneCategoryLine(new BigDecimal("1000"));

        // An ACTIVE WORKER exists but does NOT cover the estimate's category (empty category list).
        f.addActiveWorker("Adam Zielinski");

        // The stored schedule carries a single virtual bar (null member) for the uncovered category.
        f.createSchedule(projectId, bars -> bars.add(f.makeBar(f.category, null, 2, 8)));

        ScheduleView view = projectScheduleService.getView(projectId);

        ScheduleRowView row = singleRow(view);
        assertThat(row.covered()).isFalse();
        assertThat(row.bars()).singleElement().satisfies(bar -> {
            assertThat(bar.projectMemberId()).isNull();     // virtual uncovered bar (D-uncovered)
            assertThat(bar.workerName()).isNull();
            assertThat(bar.startDay()).isEqualTo(1.0);       // 2 / 2
            assertThat(bar.durationDays()).isEqualTo(4.0);   // 8 / 2
            assertThat(bar.finishDay()).isEqualTo(4.5);      // (2 + 8 - 1) / 2
        });
        // The worker is still in the legend even though it covers nothing.
        assertThat(view.workers()).extracting(ScheduleWorkerView::name).containsExactly("Adam Zielinski");
        assertThat(view.finishDay()).isEqualTo(4.5);
    }

    // =============================================================================================
    // R3.3 — half-day exposure precision across several 0.5-grid values; no bar -> empty bars list
    // =============================================================================================

    @Test
    @DisplayName("R3.3 — stored half-day counts are exposed as exact 0.5-multiple day values; no bar -> empty")
    void halfDayExposureIsExact() {
        Fixture f = new Fixture();
        long projectId = f.createProjectWithOneCategoryLine(new BigDecimal("1000"));
        long member = f.addActiveWorker("Half Day", f.category);

        // start half-day 1 (day 0.5), duration 1 half-day (0.5 day) -> finish (1+1-1)/2 = 0.5.
        f.createSchedule(projectId, bars -> bars.add(f.makeBar(f.category, member, 1, 1)));

        ScheduleView view = projectScheduleService.getView(projectId);
        ScheduleBarView bar = singleRow(view).bars().get(0);
        assertThat(bar.startDay()).isEqualTo(0.5);
        assertThat(bar.durationDays()).isEqualTo(0.5);
        assertThat(bar.finishDay()).isEqualTo(0.5);
        assertThat(view.finishDay()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("R7.2 — a covered category with no stored bar yields covered=true and an empty bars list")
    void coveredCategoryWithoutBarHasEmptyBars() {
        Fixture f = new Fixture();
        long projectId = f.createProjectWithOneCategoryLine(new BigDecimal("1000"));
        f.addActiveWorker("Covering Worker", f.category);

        // No schedule row at all -> version 0, every row unscheduled, but still covered by the worker.
        ScheduleView view = projectScheduleService.getView(projectId);

        assertThat(view.version()).isZero();
        ScheduleRowView row = singleRow(view);
        assertThat(row.covered()).isTrue();
        assertThat(row.bars()).isEmpty();
        assertThat(view.finishDay()).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static ScheduleRowView singleRow(ScheduleView view) {
        assertThat(view.rows()).hasSize(1);
        return view.rows().get(0);
    }

    private static ScheduleBarView barOfMember(ScheduleRowView row, Long memberId) {
        return row.bars().stream()
                .filter(b -> memberId.equals(b.projectMemberId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bar for member " + memberId));
    }

    /** Per-test fixture builder; all rows carry the per-run suffix for repeatable teardown. */
    private final class Fixture {

        private final TransactionTemplate tx = new TransactionTemplate(transactionManager);
        private Long category;
        private Long workItem;
        private Long unit;
        private Long currency;
        private Long workerRole;

        /** Creates a DRAFT project with a priced estimate line in a single work category. */
        long createProjectWithOneCategoryLine(BigDecimal valueNet) {
            return tx.execute(status -> {
                CurrencyEntity cur = getOrCreatePlnCurrency();
                currency = cur.getId();

                MeasurementUnitEntity u = new MeasurementUnitEntity();
                u.setCode("M2_" + suffix());
                u.setNameRU("Кв. метр");
                u.setNamePL("Metr kw.");
                u.setActive(true);
                entityManager.persist(u);
                unit = u.getId();

                WorkCategoryEntity c = new WorkCategoryEntity();
                c.setCode("CAT_" + suffix());
                c.setOrderNo(1);
                c.setNameRU("Покраска");
                c.setNamePL("Malowanie");
                c.setActive(true);
                entityManager.persist(c);
                category = c.getId();

                WorkItemEntity wi = new WorkItemEntity();
                wi.setWorkCategory(c);
                wi.setUnit(u);
                wi.setNameRU("Работа");
                wi.setNamePL("Praca");
                wi.setCode("WI_" + suffix());
                wi.setActive(true);
                entityManager.persist(wi);
                workItem = wi.getId();

                ProjectEntity project = new ProjectEntity();
                project.setName("GetView per-worker " + runId + "-" + COUNTER.incrementAndGet());
                project.setStatus(ProjectStatus.DRAFT);
                entityManager.persist(project);

                EstimateEntity estimate = new EstimateEntity();
                estimate.setProject(project);
                estimate.setCurrency(cur);
                estimate.setStatus(EstimateStatus.DRAFT);
                entityManager.persist(estimate);

                EstimateLineEntity line = new EstimateLineEntity();
                line.setEstimate(estimate);
                line.setWorkItem(wi);
                line.setUnit(u);
                line.setLineNo(1);
                line.setUnitPrice(valueNet);
                line.setQuantity(BigDecimal.ONE);
                line.setValueNet(valueNet);
                entityManager.persist(line);

                entityManager.flush();
                return project.getId();
            });
        }

        /** Adds an ACTIVE WORKER member of the project with the given covered categories; returns its id. */
        long addActiveWorker(String name, Long... coveredCategoryIds) {
            return tx.execute(status -> {
                RoleEntity role = getOrCreateWorkerRole();

                UserEntity user = new UserEntity();
                long seq = COUNTER.incrementAndGet();
                user.setName(name);
                user.setEmail("worker" + seq + "+" + runId + "@example.com");
                user.setRole(role);
                user.setActive(true);
                user.setStatus(UserStatus.ACTIVE);
                user.setLocale(java.util.Locale.forLanguageTag("ru"));
                entityManager.persist(user);

                ProjectMemberEntity member = new ProjectMemberEntity();
                member.setUser(user);
                member.setProjectId(currentProjectId());
                member.setProjectRole(role);
                member.setAssignmentStatus(AssignmentStatus.ACTIVE);
                for (Long catId : coveredCategoryIds) {
                    member.getWorkCategories().add(
                            entityManager.getReference(WorkCategoryEntity.class, catId));
                }
                entityManager.persist(member);
                entityManager.flush();
                return member.getId();
            });
        }

        /** Creates the project schedule and adds the bars produced by {@code builder}. */
        void createSchedule(long projectId, java.util.function.Consumer<List<ProjectScheduleBarEntity>> builder) {
            tx.executeWithoutResult(status -> {
                ProjectEntity project = entityManager.getReference(ProjectEntity.class, projectId);
                ProjectScheduleEntity schedule = new ProjectScheduleEntity();
                schedule.setProject(project);
                entityManager.persist(schedule);
                List<ProjectScheduleBarEntity> bars = new java.util.ArrayList<>();
                builder.accept(bars);
                for (ProjectScheduleBarEntity bar : bars) {
                    bar.setSchedule(schedule);
                    schedule.getBars().add(bar);
                    entityManager.persist(bar);
                }
                entityManager.flush();
            });
        }

        /** A detached bar for {@code category} / {@code member} (null = virtual) on the half-day grid. */
        ProjectScheduleBarEntity makeBar(Long categoryId, Long memberId, int startHalfDays, int durationHalfDays) {
            ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
            bar.setWorkCategory(entityManager.getReference(WorkCategoryEntity.class, categoryId));
            if (memberId != null) {
                bar.setProjectMember(entityManager.getReference(ProjectMemberEntity.class, memberId));
            }
            bar.setStartHalfDays(startHalfDays);
            bar.setDurationHalfDays(durationHalfDays);
            return bar;
        }

        private long currentProjectId() {
            return entityManager.createQuery(
                            "select p.id from ProjectEntity p where p.name like :p", Long.class)
                    .setParameter("p", "%" + runId + "%")
                    .getResultList().get(0);
        }

        private CurrencyEntity getOrCreatePlnCurrency() {
            List<CurrencyEntity> existing = entityManager
                    .createQuery("select c from CurrencyEntity c where c.code = :code", CurrencyEntity.class)
                    .setParameter("code", "PLN").getResultList();
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            CurrencyEntity c = new CurrencyEntity();
            c.setCode("PLN");
            c.setSymbol("zł");
            c.setNameRU("Злотый");
            c.setNamePL("Złoty");
            c.setActive(true);
            entityManager.persist(c);
            entityManager.flush();
            return c;
        }

        private RoleEntity getOrCreateWorkerRole() {
            if (workerRole != null) {
                return entityManager.getReference(RoleEntity.class, workerRole);
            }
            List<RoleEntity> existing = entityManager
                    .createQuery("select r from RoleEntity r where r.code = :c", RoleEntity.class)
                    .setParameter("c", "WORKER").getResultList();
            RoleEntity role;
            if (!existing.isEmpty()) {
                role = existing.get(0);
            } else {
                role = new RoleEntity();
                role.setCode("WORKER");
                role.setNameRU("Работник");
                role.setNamePL("Pracownik");
                role.setSystem(true);
                entityManager.persist(role);
                entityManager.flush();
            }
            workerRole = role.getId();
            return role;
        }

        private String suffix() {
            return runId + "_" + COUNTER.incrementAndGet();
        }
    }
}
