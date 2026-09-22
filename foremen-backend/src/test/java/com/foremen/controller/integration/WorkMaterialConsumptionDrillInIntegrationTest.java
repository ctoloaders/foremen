package com.foremen.controller.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.service.permission.PermissionCache;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * End-to-end integration test for the FOR-04-19 work-catalog material-consumption <b>drill-in</b>
 * read (task 6.5, Requirements 5.4, 5.5, 10.5).
 *
 * <p>The drill-in is NOT a bespoke endpoint — it rides the generic list handler
 * {@code GET /api/work-material-consumptions} via the FOR-04-01 query DSL, filtering by
 * {@code workItem.id==<id>} AND {@code offerPackage.id==<id>} with a deliberately large {@code size}
 * so the whole (work, package) consumption list is returned unpaginated (Requirement 5.5). This test
 * seeds a small deterministic catalog for a representative work item ({@code code = "1.01"}) with a
 * priced construction analog batch in a known offer package plus a distractor work/package that must
 * be excluded, then asserts, against the real read surface:
 * <ul>
 *   <li>the filtered list returns exactly the rows of that {@code (work, package)} and excludes every
 *       other work/package row (Requirement 5.5);</li>
 *   <li>each returned row carries its drill-in grouping data — its {@code branch}, its analog-group
 *       {@code materialType} reference, {@code materialUnit}, and {@code normQty} per work-unit — so
 *       the frontend can group by branch &rarr; type (Requirement 5.4);</li>
 *   <li>each row carries its {@code justification} (localized, PL fallback) and its citation
 *       ({@code sourceType}/{@code sourceDoc}/{@code sourceRef}) (Requirement 5.4);</li>
 *   <li>the large-{@code size} request renders every row without pagination truncation
 *       (Requirement 5.5).</li>
 * </ul>
 *
 * <p><b>Permission gating (Requirement 5.5 / 10.5).</b> The drill-in is guarded by the same
 * {@code WORK_MATERIAL_CONSUMPTION}/{@code READ} pair as the list. Mirroring
 * {@code EndToEndResolutionIntegrationTest}, the gating cases authenticate with a real JWT minted for
 * a per-run role holding (or lacking) exactly that grant and assert the interceptor's decision: a
 * principal WITHOUT {@code WORK_MATERIAL_CONSUMPTION}/{@code READ} is denied {@code 403}
 * ({@code error.access.denied}); a principal WITH it is allowed through (not {@code 403}, {@code 200}).
 * The interceptor's decision is taken before the handler runs, so the gating cases do not depend on
 * the rolled-back seed being visible to the token-authenticated request.
 *
 * <p><b>Harness.</b> {@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL,
 * {@code create-drop} DDL (so Liquibase is off and this test owns its schema), and — for the drill-in
 * read assertions — {@code @WithMockUser(roles="ADMIN")} + {@code @Transactional} rollback so each
 * test seeds its own deterministic dataset without cross-test contamination (mirroring
 * {@link ConstructionMaterialPriceRangeIntegrationTest} / {@link WorkPricePivotQueryIntegrationTest}).
 * The permission-gating tests bring their own bearer token, which supersedes the class
 * {@code @WithMockUser} for those requests.
 *
 * <p><b>Read-path enrichment (Requirement 5.4).</b> The consumption read path now populates the
 * computed, read-time-only {@code typeBatchRange} (the type-level money band {@code normQty ×
 * [MIN..MAX retailNet]} over the row's analog batch — the active materials of the row's TYPE in the
 * row's package) and the analog {@code materials} list (every active material of the type in the
 * package, each carrying its {@code retailNet} and per-material {@code moneyCost = normQty ×
 * retailNet}) on the {@code WorkMaterialConsumptionDtoModel} via the
 * {@code WorkMaterialConsumptionEnrichmentResolver} stamped in the service mapper's
 * {@code @AfterMapping}. {@link #drillIn_rowsCarryTypeBatchRangeAndAnalogMaterialCosts()} asserts the
 * band {@code {min,max}} and the per-material {@code retailNet}/{@code moneyCost} for a seeded priced
 * construction batch.
 *
 * <p>Validates: Requirements 5.4 (grouping data, justification, citation, {@code typeBatchRange} +
 * per-material money cost), 5.5 (drill-in via the generic list DSL, large size, permission-gated),
 * 10.5.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkMaterialConsumptionDrillInIntegrationTest {

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

    private static final String BASE = "/api/work-material-consumptions";
    private static final String RESOURCE_CODE = "WORK_MATERIAL_CONSUMPTION";
    private static final String READ_OP = "READ";
    private static final String CREATE_OP = "CREATE";

    /** Per-run unique suffix so seeded reference codes never collide across tests / runs. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkMaterialConsumptionDao consumptionDao;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private ConstructionMaterialTypeDao constructionMaterialTypeDao;

    @Autowired
    private ConstructionMaterialDao constructionMaterialDao;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PermissionCache permissionCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so token-role codes and the shared matrix codes never collide across runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private WorkCategoryEntity createCategory() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        return workCategoryDao.save(category);
    }

    private MeasurementUnitEntity createUnit(String nameRU, String namePL) {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU(nameRU);
        unit.setNamePL(namePL);
        unit.setActive(true);
        return measurementUnitDao.save(unit);
    }

    private CurrencyEntity createCurrency() {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("C" + (COUNTER.incrementAndGet() % 1000));
        currency.setSymbol("zł");
        currency.setNameRU("Злотый");
        currency.setNamePL("Złoty");
        currency.setActive(true);
        return currencyDao.save(currency);
    }

    private OfferPackageEntity createPackage(int orderNo, String nameRU, String namePL) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(unique("OP"));
        pkg.setOrderNo(orderNo);
        pkg.setNameRU(nameRU);
        pkg.setNamePL(namePL);
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private WorkItemEntity createWorkItem(String code, WorkCategoryEntity category,
                                          MeasurementUnitEntity unit, String nameRU, String namePL) {
        WorkItemEntity item = new WorkItemEntity();
        item.setCode(code);
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU(nameRU);
        item.setNamePL(namePL);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private ConstructionMaterialTypeEntity createConstructionType(String nameRU, String namePL) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode(unique("CMT"));
        type.setNameRU(nameRU);
        type.setNamePL(namePL);
        type.setActive(true);
        return constructionMaterialTypeDao.save(type);
    }

    private ConstructionMaterialEntity createConstructionMaterial(
            ConstructionMaterialTypeEntity type, MeasurementUnitEntity unit, CurrencyEntity currency,
            BigDecimal retailNet, OfferPackageEntity... packages) {
        ConstructionMaterialEntity material = new ConstructionMaterialEntity();
        material.setNameRU("Материал");
        material.setNamePL("Materiał");
        material.setType(type);
        material.setUnit(unit);
        material.setCurrency(currency);
        material.setRetailNet(retailNet);
        material.setActive(true);
        // FOR-05-04-UI (task 2.1): the construction_material_packages M:N was collapsed, so
        // ConstructionMaterialEntity no longer carries a package binding. The varargs are retained
        // for call-site compatibility but are no longer assigned to the material.
        return constructionMaterialDao.save(material);
    }

    /**
     * A single construction consumption norm row for {@code (work, package, type)} with the given
     * {@code normQty}, a preserved {@code construction} branch, a populated bilingual justification
     * and a full citation.
     */
    private WorkMaterialConsumptionEntity createConsumption(
            WorkItemEntity workItem, OfferPackageEntity offerPackage, MeasurementUnitEntity materialUnit,
            ConstructionMaterialTypeEntity type, BigDecimal normQty) {
        WorkMaterialConsumptionEntity row = new WorkMaterialConsumptionEntity();
        row.setWorkItem(workItem);
        row.setMaterialUnit(materialUnit);
        row.setBranch(ConsumptionBranch.construction);
        row.setConstructionMaterialType(type);
        row.setNormQty(normQty);
        row.setJustificationRU("Обоснование нормы расхода");
        row.setJustificationPL("Uzasadnienie normy zużycia");
        row.setSourceType("excel");
        row.setSourceDoc("Matrix Foremen v3.0");
        row.setSourceRef("row-" + COUNTER.incrementAndGet());
        return consumptionDao.save(row);
    }

    // ---------------------------------------------------------------------------------------------
    // Drill-in read: filtering, exhaustive list, grouping data, justification + citation
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Drill-in GET ?query=workItem.id==&&offerPackage.id== returns exactly that (work, "
            + "package)'s rows and excludes other works/packages")
    void drillIn_filtersByWorkAndPackage_returnsOnlyMatchingRows() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit("м2", "m2");
        MeasurementUnitEntity kg = createUnit("кг", "kg");
        CurrencyEntity currency = createCurrency();

        OfferPackageEntity budget = createPackage(1, "Бюджет", "Budżet");
        OfferPackageEntity lux = createPackage(3, "Люкс", "Lux");

        WorkItemEntity work = createWorkItem("1.01", category, workUnit, "Работа 1.01", "Praca 1.01");
        WorkItemEntity otherWork = createWorkItem("2.01", category, workUnit, "Работа 2.01", "Praca 2.01");

        ConstructionMaterialTypeEntity glue = createConstructionType("Клей", "Klej");
        ConstructionMaterialTypeEntity tape = createConstructionType("Лента", "Taśma");

        // Priced analog batch of `glue` in the `budget` package (two analogs → a real band 10..30).
        createConstructionMaterial(glue, kg, currency, new BigDecimal("10.00"), budget);
        createConstructionMaterial(glue, kg, currency, new BigDecimal("30.00"), budget);

        // Target (work=1.01, package=budget): TWO construction rows (glue + tape).
        WorkMaterialConsumptionEntity glueRow = createConsumption(work, budget, kg, glue, new BigDecimal("5.0000"));
        WorkMaterialConsumptionEntity tapeRow = createConsumption(work, budget, kg, tape, new BigDecimal("2.0000"));

        // Distractors that MUST be excluded by the (work, package) filter:
        WorkMaterialConsumptionEntity otherPackageRow =
                createConsumption(work, lux, kg, glue, new BigDecimal("7.0000"));       // same work, other package
        WorkMaterialConsumptionEntity otherWorkRow =
                createConsumption(otherWork, budget, kg, glue, new BigDecimal("9.0000")); // other work, same package
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==" + work.getId() + " AND offerPackage.id==" + budget.getId())
                        .param("page", "0")
                        .param("size", "1000"))
                .andExpect(status().isOk())
                // exactly the two target rows are present ...
                .andExpect(jsonPath("$.content[?(@.id == " + glueRow.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + tapeRow.getId() + ")]").exists())
                // ... and the distractors are excluded ...
                .andExpect(jsonPath("$.content[?(@.id == " + otherPackageRow.getId() + ")]").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.id == " + otherWorkRow.getId() + ")]").doesNotExist())
                // ... and the exhaustive list is exactly those two rows (large size → no pagination).
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @DisplayName("Drill-in rows carry grouping data: branch, materialType (analog group), materialUnit, normQty")
    void drillIn_rowsCarryBranchTypeUnitAndNormQty() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit("м2", "m2");
        MeasurementUnitEntity kg = createUnit("кг", "kg");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity budget = createPackage(1, "Бюджет", "Budżet");
        WorkItemEntity work = createWorkItem("1.01", category, workUnit, "Работа 1.01", "Praca 1.01");
        ConstructionMaterialTypeEntity glue = createConstructionType("Клей", "Klej");
        createConstructionMaterial(glue, kg, currency, new BigDecimal("10.00"), budget);

        WorkMaterialConsumptionEntity row = createConsumption(work, budget, kg, glue, new BigDecimal("5.0000"));
        entityManager.flush();
        entityManager.clear();

        String r = "$.content[?(@.id == " + row.getId() + ")]";
        mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==" + work.getId() + " AND offerPackage.id==" + budget.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                // branch preserved on the row (grouping level 1)
                .andExpect(jsonPath(r + ".branch").value(contains("construction")))
                // materialType = the analog GROUP set for the row (grouping level 2), keyed by type id
                .andExpect(jsonPath(r + ".materialType.id").value(contains(glue.getId().intValue())))
                // the numerator material unit is exposed as a localized reference
                .andExpect(jsonPath(r + ".materialUnit.id").value(contains(kg.getId().intValue())))
                // normQty per one work-unit
                .andExpect(jsonPath(r + ".normQty").value(contains(5.0)));
    }

    @Test
    @DisplayName("Drill-in rows carry justification (PL fallback) and the citation (sourceType/sourceDoc/sourceRef)")
    void drillIn_rowsCarryJustificationAndCitation() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit("м2", "m2");
        MeasurementUnitEntity kg = createUnit("кг", "kg");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity budget = createPackage(1, "Бюджет", "Budżet");
        WorkItemEntity work = createWorkItem("1.01", category, workUnit, "Работа 1.01", "Praca 1.01");
        ConstructionMaterialTypeEntity glue = createConstructionType("Клей", "Klej");
        createConstructionMaterial(glue, kg, currency, new BigDecimal("10.00"), budget);

        WorkMaterialConsumptionEntity row = createConsumption(work, budget, kg, glue, new BigDecimal("5.0000"));
        entityManager.flush();
        entityManager.clear();

        String r = "$.content[?(@.id == " + row.getId() + ")]";
        mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==" + work.getId() + " AND offerPackage.id==" + budget.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                // justification (localized single value; PL fallback for the absent/PL locale)
                .andExpect(jsonPath(r + ".justification").value(contains("Uzasadnienie normy zużycia")))
                // citation provenance carried onto the row
                .andExpect(jsonPath(r + ".sourceType").value(contains("excel")))
                .andExpect(jsonPath(r + ".sourceDoc").value(contains("Matrix Foremen v3.0")))
                .andExpect(jsonPath(r + ".sourceRef").value(hasItem(org.hamcrest.Matchers.startsWith("row-"))));
    }

    @Test
    @DisplayName("Drill-in rows carry the computed typeBatchRange {min,max} and analog materials with "
            + "retailNet + moneyCost (= normQty × retailNet)")
    void drillIn_rowsCarryTypeBatchRangeAndAnalogMaterialCosts() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit("м2", "m2");
        MeasurementUnitEntity kg = createUnit("кг", "kg");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity budget = createPackage(1, "Бюджет", "Budżet");
        WorkItemEntity work = createWorkItem("1.01", category, workUnit, "Работа 1.01", "Praca 1.01");
        ConstructionMaterialTypeEntity glue = createConstructionType("Клей", "Klej");

        // Two priced analogs of `glue` in `budget` → a real band. normQty = 5:
        //   typeBatchRange = 5 × [10.00 .. 30.00] = {50.0000, 150.0000}
        //   per-material moneyCost = 5 × retailNet = {50.0000, 150.0000}
        createConstructionMaterial(glue, kg, currency, new BigDecimal("10.00"), budget);
        createConstructionMaterial(glue, kg, currency, new BigDecimal("30.00"), budget);

        WorkMaterialConsumptionEntity row = createConsumption(work, budget, kg, glue, new BigDecimal("5.0000"));
        entityManager.flush();
        entityManager.clear();

        String r = "$.content[?(@.id == " + row.getId() + ")]";
        mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==" + work.getId() + " AND offerPackage.id==" + budget.getId())
                        .param("size", "1000"))
                .andExpect(status().isOk())
                // The type-level money band normQty × [MIN..MAX retailNet].
                .andExpect(jsonPath(r + ".typeBatchRange.min").value(contains(50.0)))
                .andExpect(jsonPath(r + ".typeBatchRange.max").value(contains(150.0)))
                // The analog batch lists both concrete materials of the type in the package ...
                .andExpect(jsonPath(r + ".materials.length()").value(contains(2)))
                // ... each carrying its retailNet ...
                .andExpect(jsonPath(r + ".materials[*].retailNet").value(hasItem(10.0)))
                .andExpect(jsonPath(r + ".materials[*].retailNet").value(hasItem(30.0)))
                // ... and its per-material money cost = normQty × retailNet.
                .andExpect(jsonPath(r + ".materials[*].moneyCost").value(hasItem(50.0)))
                .andExpect(jsonPath(r + ".materials[*].moneyCost").value(hasItem(150.0)))
                // The analog material also carries its own unit reference.
                .andExpect(jsonPath(r + ".materials[*].unit.id").value(hasItem(kg.getId().intValue())));
    }

    // ---------------------------------------------------------------------------------------------
    // Permission gating: WORK_MATERIAL_CONSUMPTION / READ (Requirement 5.5 / 10.5)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Drill-in is permission-gated: a principal lacking WORK_MATERIAL_CONSUMPTION/READ is denied 403")
    void drillIn_withoutReadGrant_isForbidden() throws Exception {
        // Holds WORK_MATERIAL_CONSUMPTION/CREATE only → lacks the READ pair the drill-in resolves to.
        RoleEntity role = persistRoleWithGrants(grant(RESOURCE_CODE, CREATE_OP));

        MvcResult result = mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==1 AND offerPackage.id==1")
                        .param("size", "1000")
                        .header("Authorization", bearer(role)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a role without WORK_MATERIAL_CONSUMPTION/READ must be denied 403 on the drill-in")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("Drill-in is permission-gated: a principal WITH WORK_MATERIAL_CONSUMPTION/READ is allowed (200)")
    void drillIn_withReadGrant_isAllowed() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant(RESOURCE_CODE, READ_OP));

        MvcResult result = mockMvc.perform(get(BASE)
                        .param("query", "workItem.id==1 AND offerPackage.id==1")
                        .param("size", "1000")
                        .header("Authorization", bearer(role)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a role holding WORK_MATERIAL_CONSUMPTION/READ must reach the drill-in (200, not 403)")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------------
    // Permission-matrix fixtures (mirrors EndToEndResolutionIntegrationTest): committed roles/grants
    // + real JWT so the ForemenPermissionEvaluator resolves the caller's matrix on its own read tx.
    // ---------------------------------------------------------------------------------------------

    private record Grant(String resource, List<String> operations) {}

    private static Grant grant(String resource, String... operations) {
        return new Grant(resource, List.of(operations));
    }

    private String bearer(RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                910_000L + COUNTER.incrementAndGet(), role.getCode(), "user+" + runId + "@example.com");
    }

    /**
     * Persists (and COMMITS, via an explicit transaction template) a role with a unique per-run code
     * holding exactly the supplied grants. The evaluator matches on resource + operation CODE, so the
     * shared resource/operation rows carry the exact matrix codes; only the role code carries the
     * per-run suffix (it keys the permission cache). The committed rows are visible to the request
     * thread's transaction the {@code ForemenPermissionEvaluator} opens on a cache miss.
     */
    private RoleEntity persistRoleWithGrants(Grant... grants) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode("WMC_ROLE_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Роль");
            role.setNamePL("Rola");
            role.setSystem(false);

            List<RoleResourceEntity> roleResources = new ArrayList<>();
            for (Grant g : grants) {
                ResourceEntity resource = getOrCreateResource(g.resource());
                List<OperationEntity> ops = new ArrayList<>();
                for (String opCode : g.operations()) {
                    ops.add(getOrCreateOperation(opCode));
                }
                RoleResourceEntity rr = new RoleResourceEntity();
                rr.setRole(role);
                rr.setResource(resource);
                rr.setOperations(ops);
                roleResources.add(rr);
            }
            role.getRoleResources().addAll(roleResources);

            entityManager.persist(role);
            entityManager.flush();
            permissionCache.invalidate(role.getCode());
            return role;
        });
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

    @AfterEach
    void cleanUpMatrix() {
        // The permission-matrix rows are committed outside the rolled-back test transaction, so remove
        // this run's roles/grants (and the shared resource/operation code rows) explicitly.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from RoleResourceEntity rr where rr.role.id in "
                                    + "(select r.id from RoleEntity r where r.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ResourceEntity res where res.code = :c")
                    .setParameter("c", RESOURCE_CODE)
                    .executeUpdate();
            entityManager.createQuery("delete from OperationEntity op where op.code in :codes")
                    .setParameter("codes", List.of(READ_OP, CREATE_OP))
                    .executeUpdate();
        });
    }
}
