package com.foremen.service.dashboard.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfig.Column;
import com.foremen.service.dashboard.config.WidgetConfig.Filter;
import com.foremen.service.dashboard.config.WidgetConfig.Op;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import com.foremen.service.dashboard.dataset.Dataset;
import com.foremen.service.dashboard.dataset.DatasetRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-10 task 7.1 — the production datasets executed by {@link DashboardQueryEngine} over seeded rows:
 * project scoping, ADMIN bypass, WORKER owner restriction ({@code WORK_REPORTS}), the null-project rule
 * ({@code PURCHASES}), NOTIFICATIONS always-own even for ADMIN, and inclusive date-range bounds.
 *
 * <p>Repeatability: every row carries a per-run id and the test runs in a rolled-back transaction.
 *
 * <p><b>Validates: Requirements 6.3, 8.1, 8.2, 8.3, 8.4, 8.5</b>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class DashboardDatasetScopingIntegrationTest {

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

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private EntityManager em;
    @Autowired
    private DatasetRegistry registry;

    private DashboardQueryEngine engine;
    private String runId;
    private ProjectEntity projectA;
    private ProjectEntity projectB;
    private UserEntity worker1;
    private UserEntity worker2;
    private UserEntity admin;
    private WorkItemEntity workItem;

    @BeforeEach
    void setUp() {
        runId = Long.toString(System.nanoTime());
        engine = new DashboardQueryEngine(em, ZoneId.of("UTC"), "PLN");
        RoleEntity role = new RoleEntity();
        role.setCode("R" + runId);
        role.setNameRU("r" + runId);
        role.setNamePL("r" + runId);
        em.persist(role);
        worker1 = user("w1", role);
        worker2 = user("w2", role);
        admin = user("adm", role);
        projectA = project("A", LocalDate.of(2031, 3, 10));
        projectB = project("B", LocalDate.of(2031, 3, 11));
        WorkCategoryEntity cat = new WorkCategoryEntity();
        cat.setCode("C" + runId);
        cat.setOrderNo(1);
        cat.setNameRU("c");
        cat.setNamePL("c");
        em.persist(cat);
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("U" + runId);
        unit.setNameRU("u");
        unit.setNamePL("u");
        em.persist(unit);
        workItem = new WorkItemEntity();
        workItem.setWorkCategory(cat);
        workItem.setUnit(unit);
        workItem.setNameRU("w" + runId);
        workItem.setNamePL("w" + runId);
        em.persist(workItem);
        em.flush();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("PROJECTS: non-admin sees only allowed projects, admin sees all, no projects sees nothing")
    void projectsScoping() {
        List<Long> mine = List.of(projectA.getId(), projectB.getId());
        assertThat(ids("PROJECTS", ctx(admin, "ADMIN", true, Set.of()), mine))
                .containsExactlyInAnyOrder(projectA.getId(), projectB.getId());
        assertThat(ids("PROJECTS", ctx(worker1, "MANAGER", false, Set.of(projectA.getId())), mine))
                .containsExactly(projectA.getId());
        assertThat(ids("PROJECTS", ctx(worker1, "MANAGER", false, Set.of()), mine)).isEmpty();
    }

    @Test
    @DisplayName("date range is inclusive on both bounds (single day, adjacent days excluded)")
    void dateRangeInclusive() {
        List<Long> mine = List.of(projectA.getId(), projectB.getId());
        LocalDate d10 = projectA.getStartDate();
        LocalDate d11 = projectB.getStartDate();
        assertThat(ids("PROJECTS", range(d10, d10), mine)).containsExactly(projectA.getId());
        assertThat(ids("PROJECTS", range(d11, d11), mine)).containsExactly(projectB.getId());
        assertThat(ids("PROJECTS", range(d10, d11), mine))
                .containsExactlyInAnyOrder(projectA.getId(), projectB.getId());
        assertThat(ids("PROJECTS", range(d10.plusDays(1), d11.minusDays(0)), mine))
                .containsExactly(projectB.getId());
        assertThat(ids("PROJECTS", range(d10.minusDays(5), d10.minusDays(1)), mine)).isEmpty();
        assertThat(ids("PROJECTS", range(d11.plusDays(1), d11.plusDays(5)), mine)).isEmpty();
    }

    @Test
    @DisplayName("WORK_REPORTS: project scoped, WORKER sees only own reports, MANAGER and ADMIN see all in scope")
    void workReportsOwnerRestriction() {
        long r1 = report(projectA, worker1);
        long r2 = report(projectA, worker2);
        long r3 = report(projectB, worker1);
        em.flush();
        List<Long> mine = List.of(r1, r2, r3);
        Set<Long> both = Set.of(projectA.getId(), projectB.getId());

        assertThat(ids("WORK_REPORTS", ctx(worker1, "WORKER", false, both), mine)).containsExactlyInAnyOrder(r1, r3);
        assertThat(ids("WORK_REPORTS", ctx(worker2, "WORKER", false, both), mine)).containsExactly(r2);
        // owner restriction is combined with project scope
        assertThat(ids("WORK_REPORTS", ctx(worker1, "WORKER", false, Set.of(projectA.getId())), mine))
                .containsExactly(r1);
        assertThat(ids("WORK_REPORTS", ctx(admin, "MANAGER", false, Set.of(projectA.getId())), mine))
                .containsExactlyInAnyOrder(r1, r2);
        assertThat(ids("WORK_REPORTS", ctx(admin, "ADMIN", true, Set.of()), mine))
                .containsExactlyInAnyOrder(r1, r2, r3);
    }

    @Test
    @DisplayName("PURCHASES: project rows by scope, no-project rows only for their uploader, ADMIN sees all")
    void purchasesNullProjectRule() {
        long inA = purchase(projectA, worker1);
        long inB = purchase(projectB, worker1);
        long orphanOfW1 = purchase(null, worker1);
        long orphanOfW2 = purchase(null, worker2);
        em.flush();
        List<Long> mine = List.of(inA, inB, orphanOfW1, orphanOfW2);
        Set<Long> onlyA = Set.of(projectA.getId());

        assertThat(ids("PURCHASES", ctx(worker1, "MANAGER", false, onlyA), mine))
                .containsExactlyInAnyOrder(inA, orphanOfW1);
        assertThat(ids("PURCHASES", ctx(worker2, "MANAGER", false, onlyA), mine))
                .containsExactlyInAnyOrder(inA, orphanOfW2);
        assertThat(ids("PURCHASES", ctx(worker2, "MANAGER", false, Set.of()), mine))
                .containsExactly(orphanOfW2);
        assertThat(ids("PURCHASES", ctx(admin, "ADMIN", true, Set.of()), mine))
                .containsExactlyInAnyOrder(inA, inB, orphanOfW1, orphanOfW2);
    }

    @Test
    @DisplayName("NOTIFICATIONS: always the caller's own rows, even for ADMIN")
    void notificationsAlwaysOwn() {
        long forAdmin = notification(admin);
        long forW1 = notification(worker1);
        long forW1b = notification(worker1);
        em.flush();
        List<Long> mine = List.of(forAdmin, forW1, forW1b);

        assertThat(ids("NOTIFICATIONS", ctx(admin, "ADMIN", true, Set.of()), mine)).containsExactly(forAdmin);
        assertThat(ids("NOTIFICATIONS", ctx(worker1, "MANAGER", false, Set.of()), mine))
                .containsExactlyInAnyOrder(forW1, forW1b);
        assertThat(ids("NOTIFICATIONS", ctx(worker2, "ADMIN", true, Set.of()), mine)).isEmpty();
    }

    @Test
    @DisplayName("truncation: a production dataset flags truncated when more rows than the limit match")
    void truncationOnProductionDataset() {
        long a = notification(admin);
        long b = notification(admin);
        long c = notification(admin);
        em.flush();
        WidgetResult r = engine.execute(dataset("NOTIFICATIONS"),
                table("NOTIFICATIONS", List.of(a, b, c), 2), ctx(admin, "ADMIN", true, Set.of()));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.truncated()).isTrue();
        WidgetResult all = engine.execute(dataset("NOTIFICATIONS"),
                table("NOTIFICATIONS", List.of(a, b, c), 3), ctx(admin, "ADMIN", true, Set.of()));
        assertThat(all.rows()).hasSize(3);
        assertThat(all.truncated()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private Dataset dataset(String code) {
        return registry.get(code).orElseThrow();
    }

    private List<Long> ids(String code, QueryContext ctx, List<Long> mine) {
        WidgetResult r = engine.execute(dataset(code), table(code, mine, null), ctx);
        return r.rows().stream().map(row -> ((Number) row.get("id")).longValue()).collect(Collectors.toList());
    }

    private static WidgetConfig table(String code, List<Long> ids, Integer limit) {
        return new WidgetConfig(1, code, Visualization.TABLE, null, List.of(new Column("id", null, null)),
                null, null, List.of(new Filter("id", Op.IN, JSON.valueToTree(ids))), null, null, null, limit,
                null, null);
    }

    private QueryContext ctx(UserEntity u, String role, boolean isAdmin, Set<Long> allowed) {
        return new QueryContext(u.getId(), role, allowed, isAdmin, null, null, null, "pl");
    }

    private QueryContext range(LocalDate from, LocalDate to) {
        return new QueryContext(admin.getId(), "ADMIN", Set.of(), true, from, to, null, "pl");
    }

    private UserEntity user(String key, RoleEntity role) {
        UserEntity u = new UserEntity();
        u.setName(key + " " + runId);
        u.setEmail(key + "+" + runId + "@example.com");
        u.setRole(role);
        u.setActive(true);
        u.setStatus(UserStatus.ACTIVE);
        u.setLocale(Locale.forLanguageTag("pl"));
        em.persist(u);
        return u;
    }

    private ProjectEntity project(String key, LocalDate start) {
        ProjectEntity p = new ProjectEntity();
        p.setName("P" + key + " " + runId);
        p.setAddress("ul. Testowa " + runId);
        p.setStatus(ProjectStatus.ACTIVE);
        p.setStartDate(start);
        em.persist(p);
        return p;
    }

    private long report(ProjectEntity project, UserEntity workerUser) {
        WorkReportEntity w = new WorkReportEntity();
        w.setProject(project);
        w.setWorkItem(workItem);
        w.setAuthor(workerUser);
        w.setWorker(workerUser);
        w.setPerformedFrom(LocalDate.of(2031, 3, 10));
        w.setPerformedTo(LocalDate.of(2031, 3, 10));
        w.setStatus(WorkReportStatus.SUBMITTED);
        em.persist(w);
        em.flush();
        return w.getId();
    }

    private long purchase(ProjectEntity project, UserEntity uploader) {
        ProcurementDocumentEntity d = new ProcurementDocumentEntity();
        d.setProject(project);
        d.setUploadedBy(uploader);
        d.setRawObjectKey("raw/" + runId + "/" + System.nanoTime());
        d.setSourceMimeType("application/pdf");
        d.setStatus(ProcessingStatus.UPLOADED);
        d.setUploadedAt(OffsetDateTime.now());
        em.persist(d);
        em.flush();
        return d.getId();
    }

    private long notification(UserEntity recipient) {
        NotificationEntity n = new NotificationEntity();
        n.setRecipient(recipient);
        n.setType("it.dashboard." + runId);
        em.persist(n);
        em.flush();
        return n.getId();
    }
}
