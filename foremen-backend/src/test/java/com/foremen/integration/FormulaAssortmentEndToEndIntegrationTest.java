package com.foremen.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.EstimateLineRoomQtyService;
import com.foremen.service.formula.FormulaRoomQtyDeriver.DerivationTrace;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * End-to-end integration test for FOR-05-04 (task 20.3): seed a work + default formula + a
 * cross-work reference -&gt; add an estimate line (single price copied, no ELPP rows) -&gt;
 * derive a room quantity from the formula -&gt; reject a cyclic formula at save -&gt; assortment
 * CRUD -&gt; package zł/m² recomputed from current data.
 *
 * <p>Mirrors the repo's established {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc}
 * + {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} harness (see
 * {@link EstimateEndToEndIntegrationTest}). Under the {@code integration-test} profile Liquibase
 * is disabled and Hibernate {@code create-drop} builds the schema against a Testcontainers
 * PostgreSQL instance, so every fixture row is created programmatically. All formula/override/
 * estimate-line/assortment writes go through the real HTTP + security + ABAC stack via
 * {@link MockMvc} with an ADMIN-role JWT (ADMIN bypasses the permission matrix in the evaluator).
 * The one step with no REST endpoint —
 * {@link EstimateLineRoomQtyService#deriveRoomQuantitiesForRoom(Long, Long)} — is invoked
 * directly via the autowired, real Spring-wired service (per task 20.1's javadoc, this is the
 * documented integration point), still exercising the real DAOs and the real
 * {@code FormulaRoomQtyDeriver}/{@code FormulaEvaluationPlanner}/{@code FormulaEvaluator}
 * pipeline.
 *
 * <p>Repeatability: every fixture row carries a unique per-test-run id (UUID substring +
 * counter), and {@link #cleanUp()} removes every row this test created (deepest-FK-first) after
 * each test, so the suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 3.3, 5.1, 5.3, 6.3, 6.4, 6.5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class FormulaAssortmentEndToEndIntegrationTest {

    private static final String ESTIMATES_PATH = "/api/estimates";
    private static final String ESTIMATE_LINES_PATH = "/api/estimate-lines";
    private static final String WORK_VOLUME_FORMULAS_PATH = "/api/work-volume-formulas";
    private static final String ASSORTMENT_GROUPS_PATH = "/api/assortment-groups";
    private static final String ASSORTMENT_POSITIONS_PATH = "/api/assortment-positions";

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
    private ProjectDao projectDao;

    @Autowired
    private RoomDao roomDao;

    @Autowired
    private RoomTypeDao roomTypeDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkPriceDao workPriceDao;

    @Autowired
    private EstimateLineRoomQtyService estimateLineRoomQtyService;

    @Autowired
    private com.foremen.dao.WorkVolumeFormulaDao workVolumeFormulaDao;

    @Autowired
    private com.foremen.dao.AssortmentGroupDao assortmentGroupDao;

    @Autowired
    private com.foremen.dao.MaterialTypeDao materialTypeDao;

    @Autowired
    private com.foremen.dao.AssortmentPositionDao assortmentPositionDao;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so fixture codes/names never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private String adminAccessToken() {
        return jwtTokenProvider.generateAccessToken(
                999_999L, "ADMIN", "admin+" + runId + "@example.com");
    }

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from EstimateLineRoomQtyEntity rq where rq.line.estimate.project.id in "
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
                            "delete from RoomEntity r where r.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from AssortmentPositionPriceEntity app "
                                    + "where app.position.group.nameRU like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from AssortmentPositionEntity ap where ap.group.nameRU like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from AssortmentGroupEntity ag where ag.nameRU like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MaterialTypeEntity mt where mt.nameRU like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // Work item codes are WorkRef-shaped ("X1"/"X2" + digits derived from runId, not the
            // raw runId string itself — see workACode/workBCode below), so they are matched here
            // via their owning WorkCategoryEntity's code (which DOES carry the full runId)
            // instead of a "like %runId%" match on WorkItemEntity.code.
            entityManager.createQuery(
                            "delete from WorkPackageOverrideEntity wpo where wpo.workItem.workCategory.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from WorkVolumeFormulaEntity wvf where wvf.workItem.workCategory.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from WorkPriceEntity wp where wp.workItem.workCategory.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.workCategory.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from OfferPackageEntity op where op.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity mu where mu.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The shared "PLN" currency fixture is intentionally NOT deleted here (get-or-create,
            // reused across runs — mirrors EstimateEndToEndIntegrationTest).
        });
    }

    @Test
    @DisplayName("Formula + assortment end-to-end: default formula + cross-work reference -> "
            + "estimate line single-price copy -> formula-derived room quantity -> cyclic "
            + "formula rejected at save -> assortment CRUD -> package zł/m² recomputation")
    void formulaAssortmentEndToEnd() throws Exception {
        String token = adminAccessToken();

        // --- Fixtures: currency, unit, TWO offer packages, work category, two work items (with
        //     WorkRef-shaped codes), a WorkPrice for workA, a project, room type, and a room ---
        CurrencyEntity currency = persistCurrency();
        MeasurementUnitEntity unit = persistMeasurementUnit();
        OfferPackageEntity packageOne = persistOfferPackage("BUDGET", 1);
        OfferPackageEntity packageTwo = persistOfferPackage("LUX", 2);
        WorkCategoryEntity workCategory = persistWorkCategory();

        // Codes shaped like valid WorkRef tokens (uppercase letters + digits) so a formula can
        // cross-reference them by code, per FormulaTokenizer.WORK_REF_PATTERN. runIdDigits is a
        // fixed-length numeric suffix derived from the run id's hash so the codes stay unique
        // per run while remaining pure [A-Z]+[0-9]+ tokens (the raw UUID-derived runId itself may
        // contain letters that would break the cell-notation shape if used directly).
        String runIdDigits = String.format("%04d", Math.abs(runId.hashCode()) % 10_000);
        String workACode = "X1" + runIdDigits;
        String workBCode = "X2" + runIdDigits;
        WorkItemEntity workA = persistWorkItem(workACode, workCategory, unit);
        WorkItemEntity workB = persistWorkItem(workBCode, workCategory, unit);

        BigDecimal catalogPrice = new BigDecimal("150.00");
        WorkPriceEntity workPriceA = persistWorkPrice(workA, currency, catalogPrice);

        ProjectEntity project = persistProject();
        RoomTypeEntity roomType = persistRoomType();
        BigDecimal roomFloorArea = new BigDecimal("20.00");
        RoomEntity room = persistRoom(project, roomType, roomFloorArea);

        // --- 1) Default formula for workA ("floorArea") + a cross-work reference from workB ---
        MvcResult formulaAResult = mockMvc.perform(post(WORK_VOLUME_FORMULAS_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkVolumeFormulaCreateBody(workA.getId(), "floorArea"))))
                .andReturn();
        assertThat(formulaAResult.getResponse().getStatus())
                .as("POST /api/work-volume-formulas for workA (default formula 'floorArea') must succeed (200)")
                .isEqualTo(200);

        MvcResult formulaBResult = mockMvc.perform(post(WORK_VOLUME_FORMULAS_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkVolumeFormulaCreateBody(workB.getId(), workACode))))
                .andReturn();
        assertThat(formulaBResult.getResponse().getStatus())
                .as("POST /api/work-volume-formulas for workB (cross-work reference to workA) must succeed (200)")
                .isEqualTo(200);

        // --- 2) Add an estimate line for workA (priced work): single-price copy, no ELPP rows ---
        MvcResult getOrCreateEstimateResult = mockMvc.perform(get(ESTIMATES_PATH + "/project/" + project.getId())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(getOrCreateEstimateResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode estimateJson = objectMapper.readTree(getOrCreateEstimateResult.getResponse().getContentAsString());
        long estimateId = estimateJson.path("id").asLong();

        EstimateLineCreateBody lineABody = new EstimateLineCreateBody(
                estimateId, workA.getId(), workPriceA.getId(), unit.getId(), 1, "e2e line A", catalogPrice);
        MvcResult createLineAResult = mockMvc.perform(post(ESTIMATE_LINES_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lineABody)))
                .andReturn();
        assertThat(createLineAResult.getResponse().getStatus())
                .as("POST /api/estimate-lines for workA must succeed (200)")
                .isEqualTo(200);
        JsonNode lineAJson = objectMapper.readTree(createLineAResult.getResponse().getContentAsString());
        assertThat(lineAJson.path("unitPrice").decimalValue())
                .as("EstimateLine.unitPrice must equal the work's single catalog price (R5.1), no fallback")
                .isEqualByComparingTo(catalogPrice);
        // No ELPP table exists anymore under create-drop (FOR-05-04 retirement, R8.2/R8.6) — the
        // absence of any query against estimate_line_package_prices IS the proof: the schema
        // itself no longer has the table, so no per-package price row could possibly exist.

        // Also add a line for workB so the room's reference graph includes both works (R3.1, R3.2).
        EstimateLineCreateBody lineBBody = new EstimateLineCreateBody(
                estimateId, workB.getId(), null, unit.getId(), 2, "e2e line B", BigDecimal.ZERO);
        MvcResult createLineBResult = mockMvc.perform(post(ESTIMATE_LINES_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lineBBody)))
                .andReturn();
        assertThat(createLineBResult.getResponse().getStatus())
                .as("POST /api/estimate-lines for workB must succeed (200)")
                .isEqualTo(200);

        // --- 3) Derive room quantities from the formula (R5.3) ---
        java.util.Map<String, DerivationTrace> traces =
                estimateLineRoomQtyService.deriveRoomQuantitiesForRoom(room.getId(), packageOne.getId());
        assertThat(traces).as("derivation must cover both workA and workB refs").containsKeys(workACode, workBCode);
        assertThat(traces.get(workACode).resolvedValue())
                .as("workA's formula ('floorArea') must resolve to the room's floor area")
                .isEqualByComparingTo(roomFloorArea);
        assertThat(traces.get(workBCode).resolvedValue())
                .as("workB's formula (cross-reference to workA) must resolve to workA's resolved value, "
                        + "i.e. the room's floor area")
                .isEqualByComparingTo(roomFloorArea);

        MvcResult lineAAfterDeriveResult = mockMvc.perform(get(ESTIMATE_LINES_PATH + "/" + lineAJson.path("id").asLong())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode lineAAfterDeriveJson =
                objectMapper.readTree(lineAAfterDeriveResult.getResponse().getContentAsString());
        assertThat(lineAAfterDeriveJson.path("quantity").decimalValue())
                .as("workA's EstimateLine.quantity must reflect the derived room quantity")
                .isEqualByComparingTo(roomFloorArea);

        // --- 4) Reject a cyclic formula at save (R3.3): update workA's formula to reference workB,
        //         creating workA -> workB -> workA ---
        // WorkVolumeFormulaCreateResponse deliberately echoes no "id" field, so the formula's id
        // is resolved via the DAO (autowired) rather than parsed off the create response.
        long formulaAId = workVolumeFormulaDao.findByWorkItemId(workA.getId())
                .orElseThrow(() -> new AssertionError("workA's default formula must exist"))
                .getId();
        MvcResult cyclicUpdateResult = mockMvc.perform(put(WORK_VOLUME_FORMULAS_PATH + "/" + formulaAId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkVolumeFormulaUpdateBody(workA.getId(), workBCode))))
                .andReturn();
        assertThat(cyclicUpdateResult.getResponse().getStatus())
                .as("updating workA's formula to reference workB (creating workA -> workB -> workA) "
                        + "must be rejected with 409 error.formula.cycle (R3.3)")
                .isEqualTo(409);
        JsonNode cyclicBody = objectMapper.readTree(cyclicUpdateResult.getResponse().getContentAsString());
        assertThat(cyclicBody.path("message").asText())
                .as("the 409 body must carry the resolved error.formula.cycle message (PL or RU locale)")
                .satisfiesAnyOf(
                        m -> assertThat(m).containsIgnoringCase("cykl"),
                        m -> assertThat(m).containsIgnoringCase("цикл"));

        // --- 5) Assortment CRUD (R6.1, R6.2): group + material-type-backed positions ---
        MvcResult groupResult = mockMvc.perform(post(ASSORTMENT_GROUPS_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssortmentGroupCreateBody("Группа " + runId, "Grupa " + runId, 1,
                                        BigDecimal.ONE, "szt"))))
                .andReturn();
        assertThat(groupResult.getResponse().getStatus())
                .as("POST /api/assortment-groups must succeed (200)")
                .isEqualTo(200);
        JsonNode groupJson = objectMapper.readTree(groupResult.getResponse().getContentAsString());
        assertThat(groupJson.path("nameRU").asText()).isEqualTo("Группа " + runId);
        // AssortmentGroupCreateResponse echoes no "id" field, so the group's id is resolved via
        // the DAO (autowired) rather than parsed off the create response.
        String groupNameRU = "Группа " + runId;
        Long groupIdOrNull = null;
        for (var g : assortmentGroupDao.findAll()) {
            if (groupNameRU.equals(g.getNameRU())) {
                groupIdOrNull = g.getId();
                break;
            }
        }
        if (groupIdOrNull == null) {
            throw new AssertionError("the just-created assortment group must exist");
        }
        long groupId = groupIdOrNull;

        // Two material types (positions REQUIRE a material type). Persisted directly via the DAO —
        // material-type CRUD is not the subject under test here.
        MaterialTypeEntity materialType1 = persistMaterialType("MT1_" + runId);
        MaterialTypeEntity materialType2 = persistMaterialType("MT2_" + runId);

        // Create two GLOBAL positions in the group (shared across all packages).
        long positionId1 = createPosition(token, groupId, materialType1.getId());
        assertThat(assortmentPositionDao.findById(positionId1))
                .as("the just-created assortment position must exist").isPresent();

        // The group's reference quantity (ILOSC) is 1 (FOR-05-04-UI), so the group contribution is
        // (Σ avgPrice for the package's positions × 1) / 50.
        BigDecimal groupReferenceQty = BigDecimal.ONE;
        BigDecimal avgPrice1 = new BigDecimal("100.00");

        // --- 6) Save this package's prices via package-save, then recompute zł/m² (R6.3-6.5) ---
        savePackagePrices(token, packageOne.getCode(), groupId, groupReferenceQty,
                new PositionPriceBody[] {
                        new PositionPriceBody(positionId1, new BigDecimal("80.00"), avgPrice1,
                                new BigDecimal("120.00"))});

        BigDecimal expectedZlM2First = avgPrice1.multiply(groupReferenceQty)
                .divide(new BigDecimal("50"), 2, RoundingMode.HALF_UP);
        MvcResult zlM2FirstResult = mockMvc.perform(get(ASSORTMENT_POSITIONS_PATH + "/package-zl-m2")
                        .header("Authorization", "Bearer " + token)
                        .param("packageCode", packageOne.getCode()))
                .andReturn();
        assertThat(zlM2FirstResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode zlM2FirstJson = objectMapper.readTree(zlM2FirstResult.getResponse().getContentAsString());
        assertThat(zlM2FirstJson.path("value").decimalValue())
                .as("package zł/m² must equal (Σ avgPrice × group.referenceQty) / 50, round2 (R6.3, R6.4)")
                .isEqualByComparingTo(expectedZlM2First);

        // Add a second position with its own price for the SAME package/group and confirm the
        // value CHANGES (R6.5, proving fresh recomputation rather than a cached total). Both
        // positions share one group, so the group contribution is (avg1 + avg2) × referenceQty / 50.
        long positionId2 = createPosition(token, groupId, materialType2.getId());
        BigDecimal avgPrice2 = new BigDecimal("50.00");
        savePackagePrices(token, packageOne.getCode(), groupId, groupReferenceQty,
                new PositionPriceBody[] {
                        new PositionPriceBody(positionId1, new BigDecimal("80.00"), avgPrice1,
                                new BigDecimal("120.00")),
                        new PositionPriceBody(positionId2, new BigDecimal("40.00"), avgPrice2,
                                new BigDecimal("60.00"))});

        BigDecimal expectedZlM2Second = avgPrice1.add(avgPrice2).multiply(groupReferenceQty)
                .divide(new BigDecimal("50"), 2, RoundingMode.HALF_UP);
        MvcResult zlM2SecondResult = mockMvc.perform(get(ASSORTMENT_POSITIONS_PATH + "/package-zl-m2")
                        .header("Authorization", "Bearer " + token)
                        .param("packageCode", packageOne.getCode()))
                .andReturn();
        assertThat(zlM2SecondResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode zlM2SecondJson = objectMapper.readTree(zlM2SecondResult.getResponse().getContentAsString());
        assertThat(zlM2SecondJson.path("value").decimalValue())
                .as("adding a second priced position must CHANGE the computed package zł/m² (R6.5, no caching)")
                .isEqualByComparingTo(expectedZlM2Second);
        assertThat(zlM2SecondJson.path("value").decimalValue())
                .as("the recomputed value must differ from the first snapshot")
                .isNotEqualByComparingTo(zlM2FirstJson.path("value").decimalValue());

        // packageTwo (no price rows) has zero contribution, sanity-checking the sum is scoped to
        // packageCode, not global — even though the positions are global.
        MvcResult zlM2PackageTwoResult = mockMvc.perform(get(ASSORTMENT_POSITIONS_PATH + "/package-zl-m2")
                        .header("Authorization", "Bearer " + token)
                        .param("packageCode", packageTwo.getCode()))
                .andReturn();
        assertThat(zlM2PackageTwoResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode zlM2PackageTwoJson = objectMapper.readTree(zlM2PackageTwoResult.getResponse().getContentAsString());
        assertThat(zlM2PackageTwoJson.path("value").decimalValue())
                .as("a package with no assortment position prices must compute a zero zł/m² contribution")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Creates a global assortment position via the REST API and returns its resolved id. */
    private long createPosition(String token, long groupId, long materialTypeId) throws Exception {
        MvcResult result = mockMvc.perform(post(ASSORTMENT_POSITIONS_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssortmentPositionCreateBody(groupId, materialTypeId, null))))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("POST /api/assortment-positions must succeed (200)")
                .isEqualTo(200);
        // The create response echoes no id; resolve the just-created position by (group, material
        // type) via the DAO.
        for (var p : assortmentPositionDao.findAll()) {
            if (p.getGroup() != null && groupId == p.getGroup().getId()
                    && p.getMaterialType() != null && materialTypeId == p.getMaterialType().getId()) {
                return p.getId();
            }
        }
        throw new AssertionError("the just-created assortment position must exist");
    }

    /** Saves this package's per-position prices via POST /package-save. */
    private void savePackagePrices(String token, String packageCode, long groupId,
                                   BigDecimal referenceQty, PositionPriceBody[] positions) throws Exception {
        PackageSaveBody body = new PackageSaveBody(new PackageSaveGroupBody[] {
                new PackageSaveGroupBody(groupId, referenceQty, "szt", positions)});
        MvcResult result = mockMvc.perform(post(ASSORTMENT_POSITIONS_PATH + "/package-save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("packageCode", packageCode)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("POST /api/assortment-positions/package-save must succeed (200)")
                .isEqualTo(200);
    }

    private MaterialTypeEntity persistMaterialType(String code) {
        MaterialTypeEntity materialType = new MaterialTypeEntity();
        materialType.setCode(code);
        materialType.setNameRU("Тип " + runId);
        materialType.setNamePL("Typ " + runId);
        materialType.setActive(true);
        return materialTypeDao.save(materialType);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private CurrencyEntity persistCurrency() {
        return currencyDao.findByCode("PLN").orElseGet(() -> {
            CurrencyEntity currency = new CurrencyEntity();
            currency.setCode("PLN");
            currency.setSymbol("zł");
            currency.setNameRU("Злотый");
            currency.setNamePL("Złoty");
            currency.setActive(true);
            return currencyDao.save(currency);
        });
    }

    private MeasurementUnitEntity persistMeasurementUnit() {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("M2_" + runId);
        unit.setNameRU("Кв. метр " + runId);
        unit.setNamePL("Metr kw. " + runId);
        unit.setActive(true);
        return measurementUnitDao.save(unit);
    }

    private OfferPackageEntity persistOfferPackage(String label, int orderNo) {
        OfferPackageEntity offerPackage = new OfferPackageEntity();
        offerPackage.setCode(label + "_" + runId);
        offerPackage.setOrderNo(orderNo);
        offerPackage.setNameRU(label + " " + runId);
        offerPackage.setNamePL(label + " " + runId);
        offerPackage.setActive(true);
        return offerPackageDao.save(offerPackage);
    }

    private WorkCategoryEntity persistWorkCategory() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode("CAT_" + runId);
        category.setOrderNo(1);
        category.setNameRU("Категория " + runId);
        category.setNamePL("Kategoria " + runId);
        category.setActive(true);
        return workCategoryDao.save(category);
    }

    private WorkItemEntity persistWorkItem(String code, WorkCategoryEntity category, MeasurementUnitEntity unit) {
        WorkItemEntity item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU("Работа " + code);
        item.setNamePL("Praca " + code);
        item.setCode(code);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private WorkPriceEntity persistWorkPrice(WorkItemEntity workItem, CurrencyEntity currency, BigDecimal netPrice) {
        WorkPriceEntity workPrice = new WorkPriceEntity();
        workPrice.setWorkItem(workItem);
        workPrice.setCurrency(currency);
        workPrice.setNetPrice(netPrice);
        return workPriceDao.save(workPrice);
    }

    private ProjectEntity persistProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("E2E Formula Project " + runId + "-" + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.DRAFT);
        return projectDao.save(project);
    }

    private RoomTypeEntity persistRoomType() {
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode("ROOMTYPE_" + runId);
        roomType.setNameRU("Комната " + runId);
        roomType.setNamePL("Pokój " + runId);
        roomType.setActive(true);
        return roomTypeDao.save(roomType);
    }

    private RoomEntity persistRoom(ProjectEntity project, RoomTypeEntity roomType, BigDecimal floorArea) {
        RoomEntity room = new RoomEntity();
        room.setProject(project);
        room.setRoomType(roomType);
        room.setLabel("E2E Room " + runId);
        room.setFloorArea(floorArea);
        return roomDao.save(room);
    }

    // ------------------------------------------------------------------
    // request bodies (records mirror the API contract; avoid depending on internal DTO records)
    // ------------------------------------------------------------------

    private record EstimateLineCreateBody(Long estimateId, Long workItemId, Long workPriceId, Long unitId,
                                           Integer lineNo, String comment, BigDecimal unitPrice) {}

    private record WorkVolumeFormulaCreateBody(Long workItemId, String sourceText) {}

    private record WorkVolumeFormulaUpdateBody(Long workItemId, String sourceText) {}

    private record AssortmentGroupCreateBody(String nameRU, String namePL, Integer sortOrder,
                                             BigDecimal referenceQty, String referenceUnit) {}

    private record AssortmentPositionCreateBody(Long assortmentGroupId, Long materialTypeId,
                                                 Integer sortOrder) {}

    private record PackageSaveBody(PackageSaveGroupBody[] groups) {}

    private record PackageSaveGroupBody(Long groupId, BigDecimal referenceQty, String referenceUnit,
                                         PositionPriceBody[] positions) {}

    private record PositionPriceBody(Long positionId, BigDecimal minPrice, BigDecimal avgPrice,
                                      BigDecimal maxPrice) {}
}
