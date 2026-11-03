package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.PayrollController;
import com.foremen.controller.WorkerPayoutController;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkReportEntity;
import com.foremen.dao.model.WorkReportInputMode;
import com.foremen.dao.model.WorkReportRoomEntity;
import com.foremen.dao.model.WorkReportStatus;
import com.foremen.dao.model.WorkerPayoutEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.permission.PermissionCache;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-11 task 8.2, reshaped by FOR-07-06 task 7.2 — payroll ABAC + startup-wiring integration
 * test over the <b>company-wide</b> payroll surface (R14.1, R14.2, R14.3, R17.2).
 *
 * <p>Boots the full application context over the real Spring MVC + Spring Security + ABAC stack
 * against a Testcontainers PostgreSQL ({@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc}
 * + {@code @ActiveProfiles("integration-test")}). Under that profile Liquibase is disabled and
 * Hibernate {@code create-drop} builds the schema, so the PAYROLL matrix rows are created
 * programmatically with the <b>real</b> role codes and the grant set the changelog leaves after
 * changeset 198 (the seeded matrix itself is pinned by {@link CompanyPayrollMigrationIntegrationTest}):
 * <ul>
 *   <li>MANAGER, FINANCIER — PAYROLL CREATE/READ/UPDATE/DELETE;</li>
 *   <li>FOREMAN — <b>no PAYROLL grant</b> (revoked by 198), only its unrelated grants;</li>
 *   <li>WORKER — no PAYROLL grant.</li>
 * </ul>
 *
 * <p><b>Startup (R14.1).</b> The context loading at all proves {@code PermissionAnnotationValidator}
 * found no half-annotated controller — including the reshaped {@link WorkerPayoutController} and the
 * new {@link PayrollController}.
 *
 * <p><b>FOREMAN 403 (R14.3).</b> Every {@code /api/payroll/**} and {@code /api/worker-payouts/**}
 * handler is denied by the interceptor.
 *
 * <p><b>MANAGER / FINANCIER 200 without project filter (R14.2).</b> A caller who is a member of no
 * project creates a worker-wide payout, lists it, reads its allocations, and reads the company table
 * and worker card — no project parameter, no project-scope 404.
 *
 * <p>Repeatability: every user/project/catalog row carries a unique per-run suffix; {@link #cleanUp()}
 * removes them in FK order (allocations → report lines → payouts → reports → rooms → projects →
 * users → catalog → matrix rows) after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Tag("Feature: FOR-07-06-company-payroll, task 7.2: PAYROLL ABAC matrix (FOREMAN revoked) + startup completeness")
class PayrollAbacStartupIntegrationTest {

    private static final String PAYOUTS = "/api/worker-payouts";
    private static final String PAYROLL = "/api/payroll";

    private static final AtomicLong COUNTER = new AtomicLong();

    /** The real role codes the matrix rows are created with (find-or-create, removed on clean-up). */
    private static final Set<String> ROLE_CODES = Set.of("MANAGER", "FINANCIER", "FOREMAN", "WORKER");
    private static final Set<String> RESOURCE_CODES = Set.of("PAYROLL", "WORK_REPORTS", "PROJECTS");
    private static final Set<String> OPERATION_CODES = Set.of("CREATE", "READ", "UPDATE", "DELETE");

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
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PermissionCache permissionCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(status -> {
            // Deepest-FK-first. Payouts are worker-wide (no project FK any more): allocations go
            // first, then report lines, then the payouts of this run's workers.
            exec("delete from WorkerPayoutAllocationEntity a where a.payout.id in "
                    + "(select p.id from WorkerPayoutEntity p where p.worker.email like :p)");
            exec("delete from WorkReportRoomEntity rr where rr.report.id in "
                    + "(select r.id from WorkReportEntity r where r.project.name like :p)");
            exec("delete from WorkerPayoutEntity p where p.worker.id in "
                    + "(select u.id from UserEntity u where u.email like :p)");
            exec("delete from WorkReportEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from RoomEntity rm where rm.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from WorkItemEntity wi where wi.code like :p");
            exec("delete from WorkCategoryEntity wc where wc.code like :p");
            exec("delete from WorkerTypeEntity w where w.code like :p");
            exec("delete from RoomTypeEntity rt where rt.code like :p");
            // Matrix rows (this container is private to the class, so the real codes are safe to drop).
            entityManager.createQuery("delete from RoleResourceEntity rr where rr.role.id in "
                            + "(select r.id from RoleEntity r where r.code in :codes)")
                    .setParameter("codes", ROLE_CODES)
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code in :codes")
                    .setParameter("codes", ROLE_CODES)
                    .executeUpdate();
            entityManager.createQuery("delete from ResourceEntity res where res.code in :codes")
                    .setParameter("codes", RESOURCE_CODES)
                    .executeUpdate();
            entityManager.createQuery("delete from OperationEntity op where op.code in :codes")
                    .setParameter("codes", OPERATION_CODES)
                    .executeUpdate();
        });
        ROLE_CODES.forEach(permissionCache::invalidate);
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // ------------------------------------------------------------------
    // Startup annotation completeness (R14.1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the app context loads with WorkerPayoutController and PayrollController — "
            + "PermissionAnnotationValidator passes at startup (R14.1)")
    void contextLoads_withBothPayrollControllers() {
        assertThat(applicationContext.getBean(WorkerPayoutController.class)).isNotNull();
        assertThat(applicationContext.getBean(PayrollController.class)).isNotNull();
    }

    // ------------------------------------------------------------------
    // 401 — unauthenticated (filter chain)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated requests to /api/worker-payouts and /api/payroll yield 401")
    void unauthenticated_yields401() throws Exception {
        assertThat(status(get(PAYOUTS))).as("payout list without a token").isEqualTo(401);
        assertThat(status(get(PAYROLL + "/company")
                .param("from", LocalDate.now().withDayOfMonth(1).toString())
                .param("to", LocalDate.now().toString())))
                .as("company table without a token").isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // FOREMAN — PAYROLL revoked by 198 → 403 everywhere (R14.3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FOREMAN (PAYROLL revoked) is denied every /api/payroll/** and /api/worker-payouts/** handler with 403")
    void foreman_deniedEveryPayrollEndpoint() throws Exception {
        RoleEntity workerRole = ensureRole("WORKER");
        RoleEntity foremanRole = ensureRole("FOREMAN",
                grant("WORK_REPORTS", "CREATE", "READ", "UPDATE"), grant("PROJECTS", "READ"));
        Fixture f = seedWorkerWithLine(workerRole);
        long payoutId = persistPayout(f.workerId);
        long foremanId = persistUser("foreman", foremanRole);
        String auth = bearer(foremanId, foremanRole);

        String from = LocalDate.now().minusDays(10).toString();
        String to = LocalDate.now().minusDays(1).toString();
        String month = LocalDate.now().withDayOfMonth(1).toString();

        List<RequestBuilder> requests = List.of(
                // /api/worker-payouts/**
                get(PAYOUTS).header("Authorization", auth),
                get(PAYOUTS + "/suggest").param("workerId", String.valueOf(f.workerId))
                        .param("from", from).param("to", to).header("Authorization", auth),
                post(PAYOUTS).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(f.workerId)),
                post(PAYOUTS + "/" + payoutId + "/pay").header("Authorization", auth),
                delete(PAYOUTS + "/" + payoutId).header("Authorization", auth),
                get(PAYOUTS + "/" + payoutId + "/allocations").header("Authorization", auth),
                // /api/payroll/**
                get(PAYROLL + "/company").param("from", from).param("to", to).header("Authorization", auth),
                get(PAYROLL + "/workers/" + f.workerId + "/card").param("from", from).param("to", to)
                        .header("Authorization", auth),
                get(PAYROLL + "/workers/" + f.workerId + "/hints").param("month", month)
                        .header("Authorization", auth),
                post(PAYROLL + "/lines").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(lineBody(f.workerId, month)),
                put(PAYROLL + "/lines/1").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(lineBody(f.workerId, month)),
                delete(PAYROLL + "/lines/1").header("Authorization", auth),
                put(PAYROLL + "/workers/" + f.workerId + "/opening-balance").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":10}"));

        for (RequestBuilder request : requests) {
            MvcResult result = mockMvc.perform(request).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("FOREMAN must be denied %s %s with 403 (PAYROLL revoked, R14.3)",
                            result.getRequest().getMethod(), result.getRequest().getRequestURI())
                    .isEqualTo(403);
        }

        // Nothing was mutated by the denied writes.
        Long payouts = tx().execute(s -> entityManager.createQuery(
                        "select count(p) from WorkerPayoutEntity p where p.worker.id = :w", Long.class)
                .setParameter("w", f.workerId).getSingleResult());
        assertThat(payouts).as("the denied create/delete changed no payout").isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // WORKER — no PAYROLL grant → 403 (deny-by-default, R14.3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a WORKER (no PAYROLL grant) is denied the payout list and the company table with 403")
    void worker_deniedRead() throws Exception {
        RoleEntity workerRole = ensureRole("WORKER", grant("WORK_REPORTS", "CREATE", "READ"));
        Fixture f = seedWorkerWithLine(workerRole);
        String auth = bearer(f.workerId, workerRole);

        assertThat(status(get(PAYOUTS).header("Authorization", auth))).isEqualTo(403);
        assertThat(status(get(PAYROLL + "/company")
                .param("from", LocalDate.now().minusDays(10).toString())
                .param("to", LocalDate.now().toString())
                .header("Authorization", auth))).isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // MANAGER / FINANCIER — CRUD, company-wide (no project filter) (R14.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MANAGER (member of no project) creates, lists and reads payroll company-wide with 200")
    void manager_companyWideWithoutProjectFilter() throws Exception {
        issuerReachesCompanyWidePayroll("MANAGER");
    }

    @Test
    @DisplayName("FINANCIER (member of no project) creates, lists and reads payroll company-wide with 200")
    void financier_companyWideWithoutProjectFilter() throws Exception {
        issuerReachesCompanyWidePayroll("FINANCIER");
    }

    private void issuerReachesCompanyWidePayroll(String roleCode) throws Exception {
        RoleEntity workerRole = ensureRole("WORKER");
        RoleEntity issuerRole = ensureRole(roleCode, grant("PAYROLL", "CREATE", "READ", "UPDATE", "DELETE"));
        Fixture f = seedWorkerWithLine(workerRole);
        // The issuer has no project membership at all: the old project scope would have 404'd.
        long issuerId = persistUser(roleCode.toLowerCase(), issuerRole);
        String auth = bearer(issuerId, issuerRole);
        String from = LocalDate.now().minusDays(10).toString();
        String to = LocalDate.now().minusDays(1).toString();

        MvcResult suggest = mockMvc.perform(get(PAYOUTS + "/suggest")
                        .param("workerId", String.valueOf(f.workerId))
                        .param("from", from).param("to", to)
                        .header("Authorization", auth))
                .andReturn();
        assertThat(suggest.getResponse().getStatus()).as("%s suggest", roleCode).isEqualTo(200);

        MvcResult created = mockMvc.perform(post(PAYOUTS)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(f.workerId)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as("%s create", roleCode).isEqualTo(200);
        Number payoutId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        MvcResult list = mockMvc.perform(get(PAYOUTS)
                        .param("workerId", String.valueOf(f.workerId))
                        .header("Authorization", auth))
                .andReturn();
        assertThat(list.getResponse().getStatus()).as("%s list without projectId", roleCode).isEqualTo(200);
        List<Number> listedIds = JsonPath.read(list.getResponse().getContentAsString(), "$[*].id");
        assertThat(listedIds).extracting(Number::longValue).containsExactly(payoutId.longValue());

        MvcResult allocations = mockMvc.perform(get(PAYOUTS + "/" + payoutId + "/allocations")
                        .header("Authorization", auth))
                .andReturn();
        assertThat(allocations.getResponse().getStatus()).as("%s allocations", roleCode).isEqualTo(200);
        List<Number> allocatedProjects = JsonPath.read(
                allocations.getResponse().getContentAsString(), "$[*].projectId");
        assertThat(allocatedProjects).extracting(Number::longValue).containsExactly(f.projectId);

        MvcResult company = mockMvc.perform(get(PAYROLL + "/company")
                        .param("from", from).param("to", LocalDate.now().toString())
                        .param("workerIds", String.valueOf(f.workerId))
                        .header("Authorization", auth))
                .andReturn();
        assertThat(company.getResponse().getStatus()).as("%s company table", roleCode).isEqualTo(200);
        List<Number> rowWorkers = JsonPath.read(company.getResponse().getContentAsString(), "$.rows[*].workerId");
        assertThat(rowWorkers).extracting(Number::longValue).containsExactly(f.workerId);

        MvcResult card = mockMvc.perform(get(PAYROLL + "/workers/" + f.workerId + "/card")
                        .param("from", from).param("to", LocalDate.now().toString())
                        .header("Authorization", auth))
                .andReturn();
        assertThat(card.getResponse().getStatus()).as("%s worker card", roleCode).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // fixtures & helpers
    // ------------------------------------------------------------------

    /** A single (resource, {operations...}) grant specification for a role. */
    private record Grant(String resource, List<String> operations) {}

    private static Grant grant(String resource, String... operations) {
        return new Grant(resource, List.of(operations));
    }

    private static final class Fixture {
        long workerId;
        long projectId;
    }

    private int status(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String createBody(long workerId) {
        LocalDate from = LocalDate.now().minusDays(10);
        LocalDate to = LocalDate.now().minusDays(1); // regular: periodTo not after today
        return "{\"workerId\":" + workerId
                + ",\"periodFrom\":\"" + from + "\""
                + ",\"periodTo\":\"" + to + "\""
                + ",\"paymentChannel\":\"BANK_TRANSFER\""
                + ",\"amount\":10.00}";
    }

    private String lineBody(long workerId, String month) {
        return "{\"workerId\":" + workerId + ",\"month\":\"" + month + "\",\"name\":\"Bonus "
                + runId + "\",\"amount\":50.00}";
    }

    private String bearer(long userId, RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, role.getCode(), "user" + userId + "+" + runId + "@example.com");
    }

    /**
     * Find-or-create a role with the exact matrix code, holding exactly the supplied grants, and
     * evict its cached permission set.
     */
    private RoleEntity ensureRole(String code, Grant... grants) {
        RoleEntity role = tx().execute(status -> {
            List<RoleEntity> existing = entityManager
                    .createQuery("select r from RoleEntity r where r.code = :c", RoleEntity.class)
                    .setParameter("c", code)
                    .getResultList();
            RoleEntity r;
            if (existing.isEmpty()) {
                r = new RoleEntity();
                r.setCode(code);
                r.setNameRU("Роль " + code);
                r.setNamePL("Rola " + code);
                r.setSystem(true);
            } else {
                r = existing.get(0);
            }
            List<RoleResourceEntity> roleResources = new ArrayList<>();
            for (Grant g : grants) {
                ResourceEntity resource = getOrCreateResource(g.resource());
                List<OperationEntity> ops = new ArrayList<>();
                for (String opCode : g.operations()) {
                    ops.add(getOrCreateOperation(opCode));
                }
                RoleResourceEntity rr = new RoleResourceEntity();
                rr.setRole(r);
                rr.setResource(resource);
                rr.setOperations(ops);
                roleResources.add(rr);
            }
            r.getRoleResources().addAll(roleResources);
            if (r.getId() == null) {
                entityManager.persist(r);
            }
            entityManager.flush();
            return r;
        });
        permissionCache.invalidate(code);
        return role;
    }

    private ResourceEntity getOrCreateResource(String code) {
        List<ResourceEntity> existing = entityManager
                .createQuery("select r from ResourceEntity r where r.code = :c", ResourceEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ResourceEntity resource = new ResourceEntity();
        resource.setCode(code);
        resource.setNameRU(code + " RU " + runId);
        resource.setNamePL(code + " PL " + runId);
        entityManager.persist(resource);
        return resource;
    }

    private OperationEntity getOrCreateOperation(String code) {
        List<OperationEntity> existing = entityManager
                .createQuery("select o from OperationEntity o where o.code = :c", OperationEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        OperationEntity operation = new OperationEntity();
        operation.setCode(code);
        operation.setNameRU(code + " RU " + runId);
        operation.setNamePL(code + " PL " + runId);
        entityManager.persist(operation);
        return operation;
    }

    private long persistUser(String key, RoleEntity role) {
        return tx().execute(status -> {
            UserEntity user = newUser(key, entityManager.getReference(RoleEntity.class, role.getId()));
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
    }

    private UserEntity newUser(String key, RoleEntity role) {
        long seq = COUNTER.incrementAndGet();
        UserEntity user = new UserEntity();
        user.setName(key + " " + runId + "-" + seq);
        user.setEmail(key + seq + "+" + runId + "@example.com");
        user.setRole(role);
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setLocale(java.util.Locale.forLanguageTag("pl"));
        return user;
    }

    /** A PLANNED regular payout persisted directly (no allocations) for the worker. */
    private long persistPayout(long workerId) {
        return tx().execute(status -> {
            WorkerPayoutEntity payout = new WorkerPayoutEntity();
            payout.setWorker(entityManager.getReference(UserEntity.class, workerId));
            payout.setPayoutDate(LocalDate.now());
            payout.setPeriodFrom(LocalDate.now().minusDays(10));
            payout.setPeriodTo(LocalDate.now().minusDays(1));
            payout.setPaymentChannel(PaymentChannel.CASH);
            payout.setStatus(PayoutStatus.PLANNED);
            payout.setAdvance(false);
            payout.setAmount(new BigDecimal("5.00"));
            payout.setSuggestedAmount(new BigDecimal("5.00"));
            payout.setAdvanceApplied(BigDecimal.ZERO);
            entityManager.persist(payout);
            entityManager.flush();
            return payout.getId();
        });
    }

    /**
     * Seeds a WORKER user with one APPROVED report line (available cost) on a fresh project the
     * callers are not members of.
     */
    private Fixture seedWorkerWithLine(RoleEntity workerRole) {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            MeasurementUnitEntity unit = getOrCreateUnit("m2");
            WorkerTypeEntity base = new WorkerTypeEntity();
            base.setCode("WT_BASE_" + runId + "_" + COUNTER.incrementAndGet());
            base.setTierPct(new BigDecimal("0.40"));
            base.setBase(true);
            base.setOrderNo(0);
            base.setNameRU("Базовый");
            base.setNamePL("Bazowy");
            base.setActive(true);
            entityManager.persist(base);

            WorkCategoryEntity category = new WorkCategoryEntity();
            category.setCode("CAT_" + runId + "_" + COUNTER.incrementAndGet());
            category.setOrderNo(1);
            category.setNameRU("Категория");
            category.setNamePL("Kategoria");
            category.setActive(true);
            entityManager.persist(category);

            WorkItemEntity item = new WorkItemEntity();
            item.setWorkCategory(category);
            item.setUnit(unit);
            item.setCode("WI_" + runId + "_" + COUNTER.incrementAndGet());
            item.setNameRU("Работа");
            item.setNamePL("Praca");
            item.setActive(true);
            entityManager.persist(item);

            UserEntity worker = newUser("worker", entityManager.getReference(RoleEntity.class, workerRole.getId()));
            entityManager.persist(worker);

            ProjectEntity project = new ProjectEntity();
            project.setName("Payroll ABAC " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.ACTIVE);
            entityManager.persist(project);

            RoomTypeEntity roomType = new RoomTypeEntity();
            roomType.setCode("ROOMTYPE_" + runId + "_" + COUNTER.incrementAndGet());
            roomType.setNameRU("Комната");
            roomType.setNamePL("Pokój");
            roomType.setActive(true);
            entityManager.persist(roomType);

            RoomEntity room = new RoomEntity();
            room.setProject(project);
            room.setRoomType(roomType);
            room.setLabel("Room " + runId);
            entityManager.persist(room);

            WorkReportEntity report = new WorkReportEntity();
            report.setProject(project);
            report.setWorkItem(item);
            report.setAuthor(worker);
            report.setWorker(worker);
            report.setPerformedFrom(LocalDate.now().minusDays(5));
            report.setPerformedTo(LocalDate.now().minusDays(4));
            report.setStatus(WorkReportStatus.APPROVED);
            report.setClientUnitPrice(new BigDecimal("100.00"));
            report.setTotalQuantity(new BigDecimal("2.000"));
            report.setRevenueValue(new BigDecimal("200.00"));
            entityManager.persist(report);

            WorkReportRoomEntity line = new WorkReportRoomEntity();
            line.setReport(report);
            line.setRoom(room);
            line.setQuantity(new BigDecimal("2.000"));
            line.setInputMode(WorkReportInputMode.ABSOLUTE);
            entityManager.persist(line);
            entityManager.flush();

            f.workerId = worker.getId();
            f.projectId = project.getId();
        });
        return f;
    }

    private MeasurementUnitEntity getOrCreateUnit(String code) {
        List<MeasurementUnitEntity> existing = entityManager.createQuery(
                        "select u from MeasurementUnitEntity u where u.code = :code", MeasurementUnitEntity.class)
                .setParameter("code", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(code);
        unit.setNameRU("Ед. " + code);
        unit.setNamePL("Jedn. " + code);
        unit.setActive(true);
        entityManager.persist(unit);
        return unit;
    }
}
