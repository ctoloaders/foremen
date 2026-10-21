package com.foremen.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * End-to-end lifecycle integration test for the FOR-05-11 aggregated readiness endpoint
 * {@code GET /api/projects/{projectId}/readiness} (task 5.2).
 *
 * <p>Where {@link ProjectReadinessAbacIntegrationTest} (task 5.1) proves the <em>access</em> and
 * <em>per-role scoping</em> contract, this test proves the <em>aggregation</em> contract: it walks a
 * single project through the FOR-05 design-stage lifecycle — empty → rooms + a priced estimate line
 * → offer sent → offer approved → an ACTIVE foreman assigned → the schedule row scheduled — and,
 * re-GETting the readiness after each committed transition over the full Spring MVC + Security + ABAC
 * stack, asserts that the aggregated {@code overallPercentage}, the rolled-up {@code overallState},
 * and the per-area {@code state}/{@code percentage}/outstanding {@code items} evolve exactly as the
 * contributor rules and {@code ReadinessMath} prescribe (Requirements 4.3, 4.4, 4.7, 5.1, 5.2, 5.3,
 * 5.5, 5.6).
 *
 * <p>The caller is an <b>ADMIN</b> JWT, so the ADMIN permission bypass admits every area and all six
 * contributors (rooms, estimate, offer, documents, team, schedule) are included on every read, in
 * their declared {@code order()} (10/20/30/40/50/60). Because the walk deliberately never prepares a
 * {@code CONTRACT_*} document, the {@code documents} area stays {@code BLOCKED} throughout, which both
 * keeps the fixture tractable and demonstrates Decision D5 directly: the {@code overallPercentage}
 * climbs monotonically from {@code 0} to {@code 83} while the {@code overallState} stays
 * {@code BLOCKED} (one blocked area pins the roll-up regardless of how high the average rises).
 *
 * <p><b>Side-effect freedom (Requirements 11.1, 11.2).</b> The readiness read is strictly a read: it
 * must write no entity and append no audit row. Around the first read this test snapshots the total
 * {@code audit_log} row count and the counts of every entity the walk touches (offers, members,
 * schedule bars, rooms, estimate lines), performs several readiness GETs, and asserts <em>every</em>
 * count is byte-for-byte unchanged — proving the GET produced no audit record and mutated no data.
 *
 * <p>Harness: the repo's established {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} pattern (mirroring
 * {@link ProjectReadinessAbacIntegrationTest} and {@link EstimateEndToEndIntegrationTest}). Under the
 * {@code integration-test} profile Liquibase is disabled and the schema is built by Hibernate
 * {@code create-drop}, so every fixture row is created programmatically via the {@link EntityManager}.
 *
 * <p>Repeatability: every fixture row carries a unique per-run suffix (a per-test UUID plus a
 * counter), and {@link #cleanUp()} removes every row this test created (deepest-first in FK order)
 * after each test, so the suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 4.3, 4.4, 4.7, 5.1, 5.2, 5.3, 5.5, 5.6, 11.1, 11.2
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectReadinessLifecycleIntegrationTest {

    private static final String READINESS_PATH_TEMPLATE = "/api/projects/%d/readiness";

    private static final AtomicLong COUNTER = new AtomicLong();

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

    /** Local instance — the MOCK web-environment context does not expose an ObjectMapper bean. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so every seeded code/name never collides with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        // Bulk deletes require an active transaction; @AfterEach is not test-transaction-managed, so
        // wrap the teardown in an explicit programmatic transaction. Delete deepest-first in FK order.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from ProjectScheduleBarEntity b where b.schedule.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from ProjectScheduleEntity s where s.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from OfferEntity o where o.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateLineEntity l where l.estimate.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateEntity e where e.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from ProjectMemberEntity pm where pm.user.id in "
                                    + "(select u.id from UserEntity u where u.email like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from RoomEntity r where r.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity mu where mu.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The shared PLN currency is a get-or-create fixture reused across runs (EstimateService
            // hard-codes the code), so it is intentionally NOT deleted here.
        });
    }

    @Test
    @DisplayName("a project walked through the design lifecycle evolves overall %, state, and per-area items; "
            + "the readiness read writes nothing and appends no audit row")
    void readinessEvolvesAcrossLifecycle_andReadIsSideEffectFree() throws Exception {
        String adminAuth = "Bearer " + jwtTokenProvider.generateAccessToken(
                nextId(), "ADMIN", "admin+" + runId + "@example.com");

        // Shared catalog fixtures reused across the walk.
        CurrencyEntity currency = getOrCreatePlnCurrency();
        MeasurementUnitEntity unit = persistMeasurementUnit();
        WorkCategoryEntity workCategory = persistWorkCategory();
        WorkItemEntity workItem = persistWorkItem(workCategory, unit);

        long projectId = createProject();

        // ----------------------------------------------------------------
        // State 0 — empty project: every area BLOCKED at 0%, overall 0% / BLOCKED.
        // ----------------------------------------------------------------
        JsonNode s0 = readReadiness(projectId, adminAuth);
        assertThat(s0.path("projectId").asLong()).isEqualTo(projectId);
        // All six contributors are included for ADMIN, in declared order (Requirements 4.5, 7.1).
        assertThat(areaKeys(s0))
                .as("ADMIN sees every design-stage area in declared order")
                .containsExactly("rooms", "estimate", "offer", "documents", "team", "schedule");
        assertArea(s0, "rooms", "BLOCKED", 0, List.of("readiness.rooms.none"));
        assertArea(s0, "estimate", "BLOCKED", 0, List.of("readiness.estimate.noWorks"));
        assertArea(s0, "offer", "BLOCKED", 0, List.of("readiness.offer.notReady"));
        assertArea(s0, "documents", "BLOCKED", 0, List.of("readiness.documents.notReady"));
        // FOR-05-10b: with neither an ACTIVE FOREMAN nor an ACTIVE WORKER, the team area reports
        // both blockers together (R8.3, R8.4).
        assertArea(s0, "team", "BLOCKED", 0,
                List.of("readiness.team.noForeman", "readiness.team.noWorkers"));
        assertArea(s0, "schedule", "BLOCKED", 0, List.of("readiness.schedule.unscheduledRows"));
        assertThat(s0.path("overallPercentage").asInt())
                .as("no area ready -> overall 0%")
                .isEqualTo(0);
        assertThat(s0.path("overallState").asText())
                .as("every area BLOCKED -> overall BLOCKED")
                .isEqualTo("BLOCKED");

        // ----------------------------------------------------------------
        // State 1 — two dimensioned rooms + an estimate with one priced line (no material lines).
        // rooms -> DONE/100, estimate -> DONE/100, schedule now has one derived row (unscheduled).
        // overall = mean(100,100,0,0,0,0) = round(200/6) = 33, state still BLOCKED.
        // ----------------------------------------------------------------
        createRoom(projectId, new BigDecimal("12.50"));
        createRoom(projectId, new BigDecimal("8.00"));
        long estimateId = createEstimate(projectId, currency);
        createPricedLine(estimateId, workItem, unit, new BigDecimal("150.00"));

        JsonNode s1 = readReadiness(projectId, adminAuth);
        assertArea(s1, "rooms", "DONE", 100, List.of());
        assertArea(s1, "estimate", "DONE", 100, List.of());
        // One estimate line -> one work-category row -> rowCount 1, scheduledCount 0 (no bar yet).
        // FOR-05-10b (R8.2): with no ACTIVE WORKER covering it, that category is also an
        // Uncovered_Category, so the schedule area lists both the unscheduled-row and the
        // no-worker-for-category blockers together (R8.3, R8.4).
        assertArea(s1, "schedule", "BLOCKED", 0,
                List.of("readiness.schedule.unscheduledRows", "readiness.schedule.noWorkerForCategory"));
        assertThat(scheduleUnscheduledCount(s1))
                .as("one derived, unscheduled schedule row")
                .isEqualTo(1);
        assertArea(s1, "offer", "BLOCKED", 0, List.of("readiness.offer.notReady"));
        // FOR-05-10b: still no foreman and no worker -> both blockers present (R8.3, R8.4).
        assertArea(s1, "team", "BLOCKED", 0,
                List.of("readiness.team.noForeman", "readiness.team.noWorkers"));
        assertThat(s1.path("overallPercentage").asInt())
                .as("mean(100,100,0,0,0,0) = round(33.3) = 33")
                .isEqualTo(33);
        assertThat(s1.path("overallState").asText())
                .as("offer/documents/team/schedule still BLOCKED -> overall BLOCKED")
                .isEqualTo("BLOCKED");

        // ----------------------------------------------------------------
        // State 2 — send the offer (SENT). offer -> ON_APPROVAL -> PARTIAL/50.
        // overall = mean(100,100,50,0,0,0) = round(250/6) = 42, state BLOCKED.
        // ----------------------------------------------------------------
        long offerId = createOffer(projectId, estimateId, OfferStatus.SENT);

        JsonNode s2 = readReadiness(projectId, adminAuth);
        assertArea(s2, "offer", "PARTIAL", 50, List.of("readiness.offer.onApproval"));
        assertThat(s2.path("overallPercentage").asInt())
                .as("mean(100,100,50,0,0,0) = round(41.7) = 42")
                .isEqualTo(42);
        assertThat(s2.path("overallState").asText()).isEqualTo("BLOCKED");

        // ----------------------------------------------------------------
        // State 3 — approve the offer (APPROVED). offer -> DONE/100.
        // overall = mean(100,100,100,0,0,0) = round(300/6) = 50, state BLOCKED.
        // ----------------------------------------------------------------
        transitionOffer(offerId, OfferStatus.APPROVED);

        JsonNode s3 = readReadiness(projectId, adminAuth);
        assertArea(s3, "offer", "DONE", 100, List.of());
        assertThat(s3.path("overallPercentage").asInt())
                .as("mean(100,100,100,0,0,0) = 50")
                .isEqualTo(50);
        assertThat(s3.path("overallState").asText()).isEqualTo("BLOCKED");

        // ----------------------------------------------------------------
        // State 4 — assign an ACTIVE FOREMAN and an ACTIVE WORKER member. The FOR-05-10b team gate
        // is DONE only when both an ACTIVE FOREMAN and an ACTIVE WORKER are present (R8.1), so both
        // are assigned here for team -> DONE/100.
        // overall = mean(100,100,100,0,100,0) = round(400/6) = 67, state BLOCKED.
        // ----------------------------------------------------------------
        addActiveMember(projectId, "FOREMAN", null);
        // FOR-05-10b (R8.2): the WORKER covers the estimate's single work category, so the schedule
        // area's Uncovered_Category disappears and only the unscheduled-row blocker remains.
        addActiveMember(projectId, "WORKER", workCategory);

        JsonNode s4 = readReadiness(projectId, adminAuth);
        assertArea(s4, "team", "DONE", 100, List.of());
        // The category is now covered, so the schedule area no longer lists noWorkerForCategory —
        // only the unscheduled-row blocker remains (no bar yet).
        assertArea(s4, "schedule", "BLOCKED", 0, List.of("readiness.schedule.unscheduledRows"));
        assertThat(s4.path("overallPercentage").asInt())
                .as("mean(100,100,100,0,100,0) = round(66.7) = 67")
                .isEqualTo(67);
        assertThat(s4.path("overallState").asText()).isEqualTo("BLOCKED");

        // ----------------------------------------------------------------
        // State 5 — schedule the derived row (add a bar for the work category). schedule -> DONE/100.
        // overall = mean(100,100,100,0,100,100) = round(500/6) = 83; documents still BLOCKED so the
        // overall state stays BLOCKED (Decision D5 — percentage and state are independent).
        // ----------------------------------------------------------------
        scheduleTheRow(projectId, workCategory);

        JsonNode s5 = readReadiness(projectId, adminAuth);
        assertArea(s5, "schedule", "DONE", 100, List.of());
        assertArea(s5, "documents", "BLOCKED", 0, List.of("readiness.documents.notReady"));
        assertThat(s5.path("overallPercentage").asInt())
                .as("mean(100,100,100,0,100,100) = round(83.3) = 83")
                .isEqualTo(83);
        assertThat(s5.path("overallState").asText())
                .as("the single BLOCKED documents area pins the roll-up to BLOCKED despite an 83% headline")
                .isEqualTo("BLOCKED");

        // The headline climbed monotonically across the walk (Requirement 4.7 — every committed
        // change is reflected by the next read).
        assertThat(List.of(
                        s0.path("overallPercentage").asInt(),
                        s1.path("overallPercentage").asInt(),
                        s2.path("overallPercentage").asInt(),
                        s3.path("overallPercentage").asInt(),
                        s4.path("overallPercentage").asInt(),
                        s5.path("overallPercentage").asInt()))
                .as("the aggregated headline rises as each area is completed")
                .containsExactly(0, 33, 42, 50, 67, 83);

        // ----------------------------------------------------------------
        // Side-effect freedom (Requirements 11.1, 11.2): a readiness read writes nothing and appends
        // no audit row. Snapshot every touched count, GET several times, assert nothing changed.
        // ----------------------------------------------------------------
        long auditBefore = countAll("AuditLogEntity");
        long offersBefore = countAll("OfferEntity");
        long membersBefore = countAll("ProjectMemberEntity");
        long barsBefore = countAll("ProjectScheduleBarEntity");
        long roomsBefore = countAll("RoomEntity");
        long linesBefore = countAll("EstimateLineEntity");

        for (int i = 0; i < 3; i++) {
            readReadiness(projectId, adminAuth);
        }

        assertThat(countAll("AuditLogEntity"))
                .as("a readiness read must append no audit row (Requirement 11.1/11.2)")
                .isEqualTo(auditBefore);
        assertThat(countAll("OfferEntity"))
                .as("a readiness read must not write offers")
                .isEqualTo(offersBefore);
        assertThat(countAll("ProjectMemberEntity"))
                .as("a readiness read must not write project members")
                .isEqualTo(membersBefore);
        assertThat(countAll("ProjectScheduleBarEntity"))
                .as("a readiness read must not write schedule bars")
                .isEqualTo(barsBefore);
        assertThat(countAll("RoomEntity"))
                .as("a readiness read must not write rooms")
                .isEqualTo(roomsBefore);
        assertThat(countAll("EstimateLineEntity"))
                .as("a readiness read must not write estimate lines")
                .isEqualTo(linesBefore);
    }

    // ------------------------------------------------------------------
    // readiness read + JSON helpers
    // ------------------------------------------------------------------

    private JsonNode readReadiness(long projectId, String auth) throws Exception {
        MvcResult result = mockMvc.perform(get(readinessPath(projectId))
                        .header("Authorization", auth))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("an ADMIN readiness read of an existing project must be 200")
                .isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String readinessPath(long projectId) {
        return String.format(READINESS_PATH_TEMPLATE, projectId);
    }

    private static List<String> areaKeys(JsonNode body) {
        List<String> keys = new ArrayList<>();
        body.path("areas").forEach(area -> keys.add(area.path("key").asText()));
        return keys;
    }

    private static JsonNode area(JsonNode body, String key) {
        for (JsonNode area : body.path("areas")) {
            if (key.equals(area.path("key").asText())) {
                return area;
            }
        }
        throw new AssertionError("area '" + key + "' not present in readiness response");
    }

    /**
     * Asserts an area's state, percentage, and the exact ordered set of its outstanding-item
     * description keys (an empty list for a {@code DONE}/100 area, Requirement 1.3).
     */
    private static void assertArea(
            JsonNode body, String key, String expectedState, int expectedPercentage,
            List<String> expectedItemKeys) {
        JsonNode area = area(body, key);
        assertThat(area.path("state").asText())
                .as("area '%s' state", key)
                .isEqualTo(expectedState);
        assertThat(area.path("percentage").asInt())
                .as("area '%s' percentage", key)
                .isEqualTo(expectedPercentage);
        List<String> itemKeys = new ArrayList<>();
        area.path("items").forEach(item -> itemKeys.add(item.path("descriptionKey").asText()));
        assertThat(itemKeys)
                .as("area '%s' outstanding-item description keys", key)
                .isEqualTo(expectedItemKeys);
    }

    private static int scheduleUnscheduledCount(JsonNode body) {
        JsonNode items = area(body, "schedule").path("items");
        return items.get(0).path("params").path("count").asInt();
    }

    // ------------------------------------------------------------------
    // fixtures — all persisted + committed via explicit transactions
    // ------------------------------------------------------------------

    private long createProject() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ProjectEntity project = new ProjectEntity();
            project.setName("Readiness lifecycle " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.DRAFT);
            entityManager.persist(project);
            entityManager.flush();
            return project.getId();
        });
    }

    private void createRoom(long projectId, BigDecimal floorArea) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProjectEntity project = entityManager.getReference(ProjectEntity.class, projectId);
            RoomEntity room = new RoomEntity();
            room.setProject(project);
            room.setRoomType(getOrCreateRoomType());
            room.setLabel("Room " + runId + "-" + COUNTER.incrementAndGet());
            room.setFloorArea(floorArea);
            entityManager.persist(room);
            entityManager.flush();
        });
    }

    private long createEstimate(long projectId, CurrencyEntity currency) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ProjectEntity project = entityManager.getReference(ProjectEntity.class, projectId);
            CurrencyEntity managedCurrency = entityManager.getReference(CurrencyEntity.class, currency.getId());
            EstimateEntity estimate = new EstimateEntity();
            estimate.setProject(project);
            estimate.setCurrency(managedCurrency);
            estimate.setStatus(EstimateStatus.DRAFT);
            entityManager.persist(estimate);
            entityManager.flush();
            return estimate.getId();
        });
    }

    /**
     * A priced line (positive {@code unitPrice}) with no room quantities — so it has no material
     * (BoM) lines and the estimate's BoM sub-fraction is 100% (nothing to complete), making the
     * estimate area DONE on pricing alone (keeps the walk focused on the lifecycle, not BoM).
     */
    private void createPricedLine(long estimateId, WorkItemEntity workItem, MeasurementUnitEntity unit,
                                  BigDecimal unitPrice) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            EstimateEntity estimate = entityManager.getReference(EstimateEntity.class, estimateId);
            WorkItemEntity managedWorkItem = entityManager.getReference(WorkItemEntity.class, workItem.getId());
            MeasurementUnitEntity managedUnit = entityManager.getReference(MeasurementUnitEntity.class, unit.getId());
            EstimateLineEntity line = new EstimateLineEntity();
            line.setEstimate(estimate);
            line.setWorkItem(managedWorkItem);
            line.setUnit(managedUnit);
            line.setLineNo(1);
            line.setUnitPrice(unitPrice);
            line.setQuantity(BigDecimal.ONE);
            line.setValueNet(unitPrice);
            entityManager.persist(line);
            entityManager.flush();
        });
    }

    private long createOffer(long projectId, long estimateId, OfferStatus statusValue) {
        return new TransactionTemplate(transactionManager).execute(txStatus -> {
            ProjectEntity project = entityManager.getReference(ProjectEntity.class, projectId);
            EstimateEntity estimate = entityManager.getReference(EstimateEntity.class, estimateId);
            OfferEntity offer = new OfferEntity();
            offer.setProject(project);
            offer.setEstimate(estimate);
            offer.setRevision(1);
            offer.setStatus(statusValue);
            entityManager.persist(offer);
            entityManager.flush();
            return offer.getId();
        });
    }

    private void transitionOffer(long offerId, OfferStatus newStatus) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OfferEntity offer = entityManager.find(OfferEntity.class, offerId);
            offer.setStatus(newStatus);
            if (newStatus == OfferStatus.APPROVED) {
                offer.setApprovedRevision(offer.getRevision());
            }
            entityManager.flush();
        });
    }

    /**
     * Assigns an ACTIVE member whose project role {@code code} is exactly {@code roleCode} — the
     * literal code the FOR-05-09 team gate counts
     * ({@code ProjectMemberDao.countByProjectIdAndProjectRoleCodeAndAssignmentStatus}). The role is a
     * get-or-create shared fixture (like the PLN currency): its code is a fixed global constant, so it
     * is reused across runs and never carries the per-run suffix (and the per-run teardown's
     * {@code code like %runId%} filter never touches it). The FOR-05-10b team gate is DONE only when
     * both an ACTIVE {@code FOREMAN} and an ACTIVE {@code WORKER} are present (R8.1), so the lifecycle
     * walk assigns one of each.
     *
     * <p>FOR-05-10b (R8.2): when {@code coveredCategory} is non-null the created member is given that
     * work category in its Member_Work_Categories, so the estimate's category becomes covered. The
     * lifecycle walk uses this for the WORKER so the schedule's single category is no longer an
     * {@code Uncovered_Category} once the worker joins.
     */
    private void addActiveMember(long projectId, String roleCode, WorkCategoryEntity coveredCategory) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            RoleEntity role = getOrCreateRole(roleCode);

            UserEntity user = new UserEntity();
            long seq = COUNTER.incrementAndGet();
            user.setName(roleCode + " " + runId + "-" + seq);
            user.setEmail(roleCode.toLowerCase() + seq + "+" + runId + "@example.com");
            user.setRole(role);
            user.setActive(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setLocale(java.util.Locale.forLanguageTag("ru"));
            entityManager.persist(user);

            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setUser(user);
            member.setProjectId(projectId);
            member.setProjectRole(role);
            member.setAssignmentStatus(AssignmentStatus.ACTIVE);
            if (coveredCategory != null) {
                WorkCategoryEntity managedCategory =
                        entityManager.getReference(WorkCategoryEntity.class, coveredCategory.getId());
                member.getWorkCategories().add(managedCategory);
            }
            entityManager.persist(member);
            entityManager.flush();
        });
    }

    /**
     * Schedules the project's single derived row by creating the project schedule and a bar for the
     * estimate line's work category, so {@code scheduledCount == rowCount} and the schedule gate is
     * DONE.
     */
    private void scheduleTheRow(long projectId, WorkCategoryEntity workCategory) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProjectEntity project = entityManager.getReference(ProjectEntity.class, projectId);
            WorkCategoryEntity managedCategory =
                    entityManager.getReference(WorkCategoryEntity.class, workCategory.getId());

            ProjectScheduleEntity schedule = new ProjectScheduleEntity();
            schedule.setProject(project);
            entityManager.persist(schedule);

            ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
            bar.setSchedule(schedule);
            bar.setWorkCategory(managedCategory);
            // FOR-05-10b (D-storage): bars are stored in half-days. Day 1 / duration 5 days
            // → start half-day 2 / duration 10 half-days.
            bar.setStartHalfDays(2);
            bar.setDurationHalfDays(10);
            schedule.getBars().add(bar);
            entityManager.persist(bar);
            entityManager.flush();
        });
    }

    /**
     * Returns the shared role with the exact {@code code} the team gate counts (e.g. {@code FOREMAN}
     * / {@code WORKER}), creating it once. The code is a fixed global constant, so the role is reused
     * across runs and never carries the per-run suffix.
     */
    private RoleEntity getOrCreateRole(String code) {
        List<RoleEntity> existing = entityManager
                .createQuery("select r from RoleEntity r where r.code = :c", RoleEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU(code);
        role.setNamePL(code);
        role.setSystem(false);
        entityManager.persist(role);
        entityManager.flush();
        return role;
    }

    private CurrencyEntity getOrCreatePlnCurrency() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            List<CurrencyEntity> existing = entityManager
                    .createQuery("select c from CurrencyEntity c where c.code = :code", CurrencyEntity.class)
                    .setParameter("code", "PLN")
                    .getResultList();
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            CurrencyEntity currency = new CurrencyEntity();
            currency.setCode("PLN");
            currency.setSymbol("zł");
            currency.setNameRU("Злотый");
            currency.setNamePL("Złoty");
            currency.setActive(true);
            entityManager.persist(currency);
            entityManager.flush();
            return currency;
        });
    }

    private MeasurementUnitEntity persistMeasurementUnit() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("M2_" + runId);
            unit.setNameRU("Кв. метр " + runId);
            unit.setNamePL("Metr kw. " + runId);
            unit.setActive(true);
            entityManager.persist(unit);
            entityManager.flush();
            return unit;
        });
    }

    private WorkCategoryEntity persistWorkCategory() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            WorkCategoryEntity category = new WorkCategoryEntity();
            category.setCode("CAT_" + runId);
            category.setOrderNo(1);
            category.setNameRU("Категория " + runId);
            category.setNamePL("Kategoria " + runId);
            category.setActive(true);
            entityManager.persist(category);
            entityManager.flush();
            return category;
        });
    }

    private WorkItemEntity persistWorkItem(WorkCategoryEntity category, MeasurementUnitEntity unit) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            WorkCategoryEntity managedCategory =
                    entityManager.getReference(WorkCategoryEntity.class, category.getId());
            MeasurementUnitEntity managedUnit =
                    entityManager.getReference(MeasurementUnitEntity.class, unit.getId());
            WorkItemEntity item = new WorkItemEntity();
            item.setWorkCategory(managedCategory);
            item.setUnit(managedUnit);
            item.setNameRU("Работа " + runId);
            item.setNamePL("Praca " + runId);
            item.setCode("WI_" + runId);
            item.setActive(true);
            entityManager.persist(item);
            entityManager.flush();
            return item;
        });
    }

    private RoomTypeEntity getOrCreateRoomType() {
        List<RoomTypeEntity> existing = entityManager
                .createQuery("select rt from RoomTypeEntity rt where rt.code = :c", RoomTypeEntity.class)
                .setParameter("c", "ROOMTYPE_" + runId)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode("ROOMTYPE_" + runId);
        roomType.setNameRU("Комната " + runId);
        roomType.setNamePL("Pokój " + runId);
        roomType.setActive(true);
        entityManager.persist(roomType);
        return roomType;
    }

    /** Counts all rows of an entity in a fresh read-only transaction (snapshot for side-effect checks). */
    private long countAll(String entityName) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery("select count(e) from " + entityName + " e", Long.class)
                        .getSingleResult());
    }

    private long nextId() {
        return 930_000L + COUNTER.incrementAndGet();
    }
}
