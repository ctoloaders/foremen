package com.foremen.integration;

import java.math.BigDecimal;
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
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * End-to-end integration test for FOR-05-03 (task 15.3): create project → get-or-create estimate →
 * add line (snapshot copies package prices) → add room qty (totals recompute) → catalog price edit
 * does NOT move the snapshot → advance status past DRAFT → free edit blocked with
 * {@code error.estimate.locked}.
 *
 * <p>Mirrors the repo's established {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} harness (see
 * {@link InviteEndToEndIntegrationTest}, {@link EstimateControllersStartupIntegrationTest}). Under
 * the {@code integration-test} profile Liquibase is disabled and Hibernate {@code create-drop}
 * builds the schema against a Testcontainers PostgreSQL instance, so every fixture row (currency,
 * measurement unit, offer package, work category/item/price, room type, project, room) is created
 * programmatically rather than relying on the Liquibase seed data. All estimate-domain writes
 * (estimate, line, room-qty, status transition) go through the real HTTP + security + ABAC stack via
 * {@link MockMvc} with an ADMIN-role JWT (ADMIN bypasses the permission matrix in the evaluator, per
 * {@code ForemenPermissionEvaluator}), exercising the actual controllers/services rather than calling
 * them directly.
 *
 * <p>Repeatability: every fixture row (project name, work item code, currency/unit/package codes)
 * carries a unique per-test run id (UUID substring + counter), and {@link #cleanUp()} removes every
 * row this test created (in FK order) after each test, so the suite re-runs without manual DB
 * cleanup.
 *
 * <p>Validates: Requirements 1.5, 4.1, 4.5, 8.1, 8.2, 7.2
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class EstimateEndToEndIntegrationTest {

    private static final String ESTIMATES_PATH = "/api/estimates";
    private static final String ESTIMATE_LINES_PATH = "/api/estimate-lines";
    private static final String ESTIMATE_LINE_ROOM_QTY_PATH = "/api/estimate-line-room-qty";
    private static final String ESTIMATE_LINE_PACKAGE_PRICES_PATH = "/api/estimate-line-package-prices";

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
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so fixture codes/names never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    private String adminAccessToken() {
        return jwtTokenProvider.generateAccessToken(
                999_999L, "ADMIN", "admin+" + runId + "@example.com");
    }

    @AfterEach
    void cleanUp() {
        // FK order: estimate_line_package_prices/estimate_line_room_qty/estimate_lines/estimates are
        // removed transitively by the project delete cascade paths not being relied upon here — this
        // test deletes explicitly, deepest-first, then the catalog fixtures, then the room/project.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from EstimateLinePackagePriceHistoryEntity h where h.packagePrice.line.estimate"
                                    + ".project.id in (select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateLinePackagePriceEntity p where p.line.estimate.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
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
                            "delete from WorkPackagePriceEntity wpp where wpp.workPrice.workItem.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from WorkPriceEntity wp where wp.workItem.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.code like :p")
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
            // The shared "PLN" currency fixture (see persistCurrency()) is intentionally NOT deleted
            // here: EstimateService hard-codes it as the default currency code, so it is a
            // get-or-create fixture reused across runs, not a per-run row.
        });
    }

    @Test
    @DisplayName("Full estimate lifecycle: create project -> get-or-create estimate -> add line "
            + "(snapshot copies package price) -> add room qty (totals recompute) -> catalog price "
            + "edit does not move the snapshot -> DRAFT gate blocks further free edits")
    void estimateLifecycle_createLineRoomQtySnapshotAndDraftGate() throws Exception {
        String token = adminAccessToken();

        // --- Fixtures: currency, unit, offer package, work category/item/price, project, room ---
        CurrencyEntity currency = persistCurrency();
        MeasurementUnitEntity unit = persistMeasurementUnit();
        OfferPackageEntity offerPackage = persistOfferPackage();
        WorkCategoryEntity workCategory = persistWorkCategory();
        WorkItemEntity workItem = persistWorkItem(workCategory, unit);
        BigDecimal catalogPrice = new BigDecimal("150.00");
        WorkPriceEntity workPrice = persistWorkPrice(workItem);
        WorkPackagePriceEntity workPackagePrice =
                persistWorkPackagePrice(workPrice, offerPackage, currency, catalogPrice);

        ProjectEntity project = persistProject();
        RoomTypeEntity roomType = persistRoomType();
        RoomEntity room = persistRoom(project, roomType);

        // --- 1) getOrCreateForProject: an estimate does not exist yet -> created DRAFT/zero totals ---
        MvcResult getOrCreateResult = mockMvc.perform(get(ESTIMATES_PATH + "/project/" + project.getId())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(getOrCreateResult.getResponse().getStatus())
                .as("GET /api/estimates/project/{id} must create-or-resolve the project's estimate (200)")
                .isEqualTo(200);
        JsonNode estimateJson = objectMapper.readTree(getOrCreateResult.getResponse().getContentAsString());
        long estimateId = estimateJson.path("id").asLong();
        assertThat(estimateJson.path("status").asText())
                .as("a freshly created estimate must default to DRAFT")
                .isEqualTo("DRAFT");
        assertThat(estimateJson.path("totalNet").decimalValue())
                .as("a freshly created (line-less) estimate must have zero totalNet")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Calling get-or-create again resolves to the SAME estimate (R1.5, R1.6).
        MvcResult secondGetOrCreate = mockMvc.perform(get(ESTIMATES_PATH + "/project/" + project.getId())
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode secondEstimateJson = objectMapper.readTree(secondGetOrCreate.getResponse().getContentAsString());
        assertThat(secondEstimateJson.path("id").asLong())
                .as("a repeated get-or-create call must resolve to the same estimate id")
                .isEqualTo(estimateId);

        // --- 2) Add a line referencing the priced work item -> snapshot copies the package price ---
        BigDecimal lineUnitPrice = new BigDecimal("150.00");
        EstimateLineCreateBody lineBody = new EstimateLineCreateBody(
                estimateId, workItem.getId(), workPrice.getId(), unit.getId(), 1, "e2e line", lineUnitPrice);
        MvcResult createLineResult = mockMvc.perform(post(ESTIMATE_LINES_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lineBody)))
                .andReturn();
        assertThat(createLineResult.getResponse().getStatus())
                .as("POST /api/estimate-lines must succeed (200)")
                .isEqualTo(200);
        JsonNode lineJson = objectMapper.readTree(createLineResult.getResponse().getContentAsString());
        long lineId = lineJson.path("id").asLong();

        // A per-package price snapshot row exists for the line's package, copied from the catalog.
        // Reads through the /extended list endpoint (EstimateLinePackagePriceServiceExtendedModel),
        // deliberately avoiding the plain /find (EstimateLinePackagePriceServiceModel) endpoint,
        // whose offerPackageName @AfterMapping touches a lazy OfferPackageEntity association outside
        // any open session/transaction under this profile's open-in-view=false — a pre-existing gap
        // in that unrelated mapper, out of scope for this end-to-end test.
        MvcResult packagePricesResult = mockMvc.perform(get(ESTIMATE_LINE_PACKAGE_PRICES_PATH + "/extended")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "line.id==" + lineId)
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();
        assertThat(packagePricesResult.getResponse().getStatus())
                .as("GET /api/estimate-line-package-prices/extended filtered by line must succeed (200)")
                .isEqualTo(200);
        JsonNode packagePricesPage = objectMapper.readTree(packagePricesResult.getResponse().getContentAsString());
        JsonNode packagePriceRows = packagePricesPage.path("content");
        assertThat(packagePriceRows.size())
                .as("exactly one per-package price row must exist for the line's single offer package")
                .isEqualTo(1);
        JsonNode packagePriceRow = packagePriceRows.get(0);
        long packagePriceId = packagePriceRow.path("id").asLong();
        assertThat(packagePriceRow.path("originalUnitPrice").decimalValue())
                .as("the snapshot's originalUnitPrice must equal the catalog price copied at add-time")
                .isEqualByComparingTo(catalogPrice);
        assertThat(packagePriceRow.path("unitPrice").decimalValue())
                .as("with no discount, the effective unitPrice must equal originalUnitPrice")
                .isEqualByComparingTo(catalogPrice);
        assertThat(packagePriceRow.path("unpriced").asBoolean())
                .as("the snapshot must be priced (resolver found the catalog price)")
                .isFalse();

        // --- 3) Add a room quantity for the line -> line.quantity/valueNet + estimate totals recompute ---
        BigDecimal roomQty = new BigDecimal("4.50");
        EstimateLineRoomQtyCreateBody roomQtyBody =
                new EstimateLineRoomQtyCreateBody(lineId, room.getId(), roomQty);
        MvcResult createRoomQtyResult = mockMvc.perform(post(ESTIMATE_LINE_ROOM_QTY_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(roomQtyBody)))
                .andReturn();
        assertThat(createRoomQtyResult.getResponse().getStatus())
                .as("POST /api/estimate-line-room-qty must succeed (200)")
                .isEqualTo(200);

        BigDecimal expectedValueNet = lineUnitPrice.multiply(roomQty).setScale(2, java.math.RoundingMode.HALF_UP);

        MvcResult lineAfterQtyResult = mockMvc.perform(get(ESTIMATE_LINES_PATH + "/" + lineId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode lineAfterQtyJson = objectMapper.readTree(lineAfterQtyResult.getResponse().getContentAsString());
        assertThat(lineAfterQtyJson.path("quantity").decimalValue())
                .as("line.quantity must equal the sum of its room quantities (R2.6, R3.3)")
                .isEqualByComparingTo(roomQty);
        assertThat(lineAfterQtyJson.path("valueNet").decimalValue())
                .as("line.valueNet must be round2(unitPrice * quantity) (R2.7, R8.1)")
                .isEqualByComparingTo(expectedValueNet);

        MvcResult estimateAfterQtyResult = mockMvc.perform(get(ESTIMATES_PATH + "/" + estimateId)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode estimateAfterQtyJson = objectMapper.readTree(estimateAfterQtyResult.getResponse().getContentAsString());
        assertThat(estimateAfterQtyJson.path("totalNet").decimalValue())
                .as("estimate.totalNet must equal the sum of line valueNet after the room-qty add (R8.2)")
                .isEqualByComparingTo(expectedValueNet);

        // --- 4) Catalog price edit does NOT move the already-created snapshot (R4.5) ---
        mutateCatalogPrice(workPackagePrice.getId(), new BigDecimal("999.00"));

        MvcResult packagePriceAfterCatalogEditResult = mockMvc.perform(
                        get(ESTIMATE_LINE_PACKAGE_PRICES_PATH + "/" + packagePriceId)
                                .header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode packagePriceAfterCatalogEditJson =
                objectMapper.readTree(packagePriceAfterCatalogEditResult.getResponse().getContentAsString());
        assertThat(packagePriceAfterCatalogEditJson.path("originalUnitPrice").decimalValue())
                .as("a later catalog price edit must NOT move the already-created snapshot's "
                        + "originalUnitPrice (R4.5)")
                .isEqualByComparingTo(catalogPrice);
        assertThat(packagePriceAfterCatalogEditJson.path("unitPrice").decimalValue())
                .as("a later catalog price edit must NOT move the already-created snapshot's "
                        + "effective unitPrice (R4.5)")
                .isEqualByComparingTo(catalogPrice);

        // --- 5) Advance the estimate's status past DRAFT ---
        EstimateUpdateBody advanceStatusBody = new EstimateUpdateBody(currency.getId(), null, "PRICED");
        MvcResult advanceStatusResult = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put(ESTIMATES_PATH + "/" + estimateId)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(advanceStatusBody)))
                .andReturn();
        assertThat(advanceStatusResult.getResponse().getStatus())
                .as("PUT /api/estimates/{id} setting status=PRICED must succeed (200)")
                .isEqualTo(200);
        JsonNode advancedEstimateJson = objectMapper.readTree(advanceStatusResult.getResponse().getContentAsString());
        assertThat(advancedEstimateJson.path("status").asText())
                .as("the estimate's status must now be PRICED (past DRAFT)")
                .isEqualTo("PRICED");

        // --- 6) A further free edit is now blocked with 409 error.estimate.locked (R7.2) ---
        EstimateLineRoomQtyCreateBody blockedRoomQtyBody =
                new EstimateLineRoomQtyCreateBody(lineId, room.getId(), new BigDecimal("1.00"));
        MvcResult blockedResult = mockMvc.perform(post(ESTIMATE_LINE_ROOM_QTY_PATH)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blockedRoomQtyBody)))
                .andReturn();
        assertThat(blockedResult.getResponse().getStatus())
                .as("a free-edit write on a non-DRAFT estimate must be rejected with 409 (R7.2)")
                .isEqualTo(409);
        JsonNode blockedBody = objectMapper.readTree(blockedResult.getResponse().getContentAsString());
        assertThat(blockedBody.path("message").asText())
                .as("the 409 body must carry the resolved error.estimate.locked message (PL or RU locale)")
                .satisfiesAnyOf(
                        m -> assertThat(m).containsIgnoringCase("zablokowan"),
                        m -> assertThat(m).containsIgnoringCase("блокир"));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * Get-or-create the {@code PLN} currency. {@code EstimateService} hard-codes {@code "PLN"} as
     * the default currency code ({@link com.foremen.service.EstimateService}), so under the
     * {@code create-drop} schema (no Liquibase seed) this fixture must exist with that exact code
     * for {@code getOrCreateForProject}/line-add defaulting to succeed. Reused across runs (not
     * deleted in {@link #cleanUp()}) since the code is a fixed, globally shared constant, mirroring
     * {@code EndToEndResolutionIntegrationTest}'s get-or-create pattern for shared matrix rows.
     */
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

    private OfferPackageEntity persistOfferPackage() {
        OfferPackageEntity offerPackage = new OfferPackageEntity();
        offerPackage.setCode("BUDGET_" + runId);
        offerPackage.setOrderNo(1);
        offerPackage.setNameRU("Бюджет " + runId);
        offerPackage.setNamePL("Budżet " + runId);
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

    private WorkItemEntity persistWorkItem(WorkCategoryEntity category, MeasurementUnitEntity unit) {
        WorkItemEntity item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU("Работа " + runId);
        item.setNamePL("Praca " + runId);
        item.setCode("WI_" + runId);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private WorkPriceEntity persistWorkPrice(WorkItemEntity workItem) {
        WorkPriceEntity workPrice = new WorkPriceEntity();
        workPrice.setWorkItem(workItem);
        return workPriceDao.save(workPrice);
    }

    private WorkPackagePriceEntity persistWorkPackagePrice(WorkPriceEntity workPrice, OfferPackageEntity offerPackage,
                                                            CurrencyEntity currency, BigDecimal netPrice) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            WorkPackagePriceEntity price = new WorkPackagePriceEntity();
            price.setWorkPrice(workPrice);
            price.setOfferPackage(offerPackage);
            price.setCurrency(currency);
            price.setNetPrice(netPrice);
            entityManager.persist(price);
            entityManager.flush();
            return price;
        });
    }

    private ProjectEntity persistProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("E2E Project " + runId + "-" + COUNTER.incrementAndGet());
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

    private RoomEntity persistRoom(ProjectEntity project, RoomTypeEntity roomType) {
        RoomEntity room = new RoomEntity();
        room.setProject(project);
        room.setRoomType(roomType);
        room.setLabel("E2E Room " + runId);
        return roomDao.save(room);
    }

    /** Mutates the catalog's per-package price directly (simulating a later catalog edit, R4.5). */
    private void mutateCatalogPrice(Long workPackagePriceId, BigDecimal newNetPrice) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            WorkPackagePriceEntity price = entityManager.find(WorkPackagePriceEntity.class, workPackagePriceId);
            price.setNetPrice(newNetPrice);
            entityManager.merge(price);
        });
    }

    // ------------------------------------------------------------------
    // request bodies (records mirror the API contract; avoid depending on internal DTO records)
    // ------------------------------------------------------------------

    private record EstimateLineCreateBody(Long estimateId, Long workItemId, Long workPriceId, Long unitId,
                                           Integer lineNo, String comment, BigDecimal unitPrice) {}

    private record EstimateLineRoomQtyCreateBody(Long lineId, Long roomId, BigDecimal quantity) {}

    private record EstimateUpdateBody(Long currencyId, Long vatRateId, String status) {}
}
