package com.foremen.controller.integration;

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.pricing.SeededOfferPackages;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the FOR-04-19 work-catalog aggregation surfaced on the {@code WorkItem}
 * catalog list ({@code GET /api/work-items}) and its {@code /metadata} pivot descriptors
 * (Requirements 5.1, 5.2, 5.3, 5.6; test Requirement 10.4).
 *
 * <p>Mirrors {@link WorkPricePivotQueryIntegrationTest}: {@code @SpringBootTest} + MockMvc against a
 * Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")} (which
 * carries both {@code WORK_CATALOG}/READ and the metadata READ), {@code @Transactional} rollback for
 * isolation. Because {@link SeededOfferPackages} caches the offer-package set once for the whole
 * Spring context and this test seeds its own packages inside a rolled-back transaction, the cache is
 * reset in {@link #resetOfferPackageCache()} before each test so the aggregation and metadata see
 * this test's freshly-persisted packages rather than a stale set warmed by another test.
 *
 * <p>Each work item is bound (as in production) to a {@link WorkPriceEntity} labour price and to
 * {@link WorkMaterialConsumptionEntity} norms against a {@link ConstructionMaterialTypeEntity} whose
 * analog batch (priced {@code construction_materials} in the package) drives the construction money
 * range. The fixtures deliberately create NO finishing consumption — mirroring the current seed
 * (construction-only) — so the finishing range renders an explicit {@code 0..0}, exercising the
 * "explicit 0 where a material branch is absent" assertion naturally.
 *
 * <p>Assertions:
 * <ul>
 *   <li>the pivot field per package ({@code packagePivot.{id}}) carries the THREE prices — labour
 *       price, construction range {@code {min,max}}, finishing range {@code {min,max}};</li>
 *   <li>a branch with no consumption renders an explicit {@code 0} (finishing here);</li>
 *   <li>the construction range is present and non-negative where a priced material of the type is in
 *       the package;</li>
 *   <li>the list stays paginated over DISTINCT work items (more rows than page size → no split /
 *       duplication of a work-item row);</li>
 *   <li>{@code /metadata} advertises exactly one {@code packagePivot.{id}} descriptor per seeded
 *       offer package, each carrying the three price sub-fields.</li>
 * </ul>
 *
 * <p>Validates: Requirements 5.1, 5.2, 5.3, 5.6, 10.4
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkCatalogAggregationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    /** Per-run unique code suffix so FK fixtures never collide. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkPriceDao workPriceDao;

    @Autowired
    private WorkMaterialConsumptionDao consumptionDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private ConstructionMaterialTypeDao constructionMaterialTypeDao;

    @Autowired
    private ConstructionMaterialDao constructionMaterialDao;

    @Autowired
    private SeededOfferPackages seededOfferPackages;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Clears the {@link SeededOfferPackages} singleton cache so the aggregation/metadata reload the
     * offer packages this test persists (which live only inside the rolled-back test transaction),
     * instead of a set warmed by an earlier test in the shared context.
     */
    @BeforeEach
    void resetOfferPackageCache() {
        ReflectionTestUtils.setField(seededOfferPackages, "cached", null);
        ReflectionTestUtils.setField(seededOfferPackages, "byId", null);
    }

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private WorkCategoryEntity createCategory() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        return workCategoryDao.save(category);
    }

    private MeasurementUnitEntity createUnit() {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU("Квадратный метр");
        unit.setNamePL("Metr kwadratowy");
        unit.setActive(true);
        return measurementUnitDao.save(unit);
    }

    private WorkItemEntity createWorkItem(WorkCategoryEntity category, MeasurementUnitEntity unit,
                                          String nameRU, String namePL) {
        WorkItemEntity item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU(nameRU);
        item.setNamePL(namePL);
        item.setActive(true);
        return workItemDao.save(item);
    }

    private CurrencyEntity createCurrency() {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode("C" + COUNTER.incrementAndGet());
        currency.setSymbol("zł");
        currency.setNameRU("Злотый");
        currency.setNamePL("Złoty");
        currency.setActive(true);
        return currencyDao.save(currency);
    }

    private OfferPackageEntity createOfferPackage(int orderNo) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(unique("OP"));
        pkg.setOrderNo(orderNo);
        pkg.setNameRU("Пакет");
        pkg.setNamePL("Pakiet");
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private ConstructionMaterialTypeEntity createConstructionType() {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setCode(unique("CMT"));
        type.setNameRU("Тип");
        type.setNamePL("Typ");
        type.setActive(true);
        return constructionMaterialTypeDao.save(type);
    }

    /**
     * Persists a single labour-price aggregator for {@code item} carrying one package price per
     * supplied {@code (offerPackage, netPrice)} pair. {@code work_prices} is unique on
     * {@code work_item_id}, so a work item has exactly ONE aggregator holding all its package prices.
     */
    private void createLabourPrice(WorkItemEntity item, CurrencyEntity currency,
                                   java.util.Map<OfferPackageEntity, BigDecimal> pricesByPackage) {
        WorkPriceEntity price = new WorkPriceEntity();
        price.setWorkItem(item);
        for (java.util.Map.Entry<OfferPackageEntity, BigDecimal> entry : pricesByPackage.entrySet()) {
            WorkPackagePriceEntity member = new WorkPackagePriceEntity();
            member.setWorkPrice(price);
            member.setOfferPackage(entry.getKey());
            member.setCurrency(currency);
            member.setNetPrice(entry.getValue());
            price.getPackagePrices().add(member);
        }
        workPriceDao.save(price);
    }

    /** Convenience: one package price aggregator for {@code item}. */
    private void createLabourPrice(WorkItemEntity item, CurrencyEntity currency,
                                   OfferPackageEntity pkg, BigDecimal netPrice) {
        java.util.Map<OfferPackageEntity, BigDecimal> single = new java.util.LinkedHashMap<>();
        single.put(pkg, netPrice);
        createLabourPrice(item, currency, single);
    }

    /** Persists an active priced construction material of {@code type} offered in {@code pkg}. */
    private void createConstructionMaterial(ConstructionMaterialTypeEntity type, OfferPackageEntity pkg,
                                            MeasurementUnitEntity unit, CurrencyEntity currency,
                                            BigDecimal retailNet) {
        ConstructionMaterialEntity material = new ConstructionMaterialEntity();
        material.setNameRU("Материал");
        material.setNamePL("Materiał");
        material.setType(type);
        material.setUnit(unit);
        material.setCurrency(currency);
        material.setRetailNet(retailNet);
        material.setPackages(new HashSet<>(java.util.List.of(pkg)));
        material.setActive(true);
        constructionMaterialDao.save(material);
    }

    /** Persists a construction consumption norm for {@code (item, pkg, type)}. */
    private void createConstructionConsumption(WorkItemEntity item, OfferPackageEntity pkg,
                                               ConstructionMaterialTypeEntity type,
                                               MeasurementUnitEntity materialUnit, BigDecimal normQty) {
        WorkMaterialConsumptionEntity row = new WorkMaterialConsumptionEntity();
        row.setWorkItem(item);
        row.setOfferPackage(pkg);
        row.setMaterialUnit(materialUnit);
        row.setBranch(ConsumptionBranch.construction);
        row.setConstructionMaterialType(type);
        row.setNormQty(normQty);
        row.setSourceType("expert");
        row.setSourceDoc("doc");
        row.setSourceRef("ref");
        consumptionDao.save(row);
    }

    // --- Pivot cell carries the three prices; finishing renders explicit 0 -------------------------

    @Test
    @DisplayName("packagePivot.{id} carries labour price + construction range + explicit-0 finishing range")
    void pivotCell_carriesThreePrices_finishingExplicitZero() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit();
        MeasurementUnitEntity materialUnit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage(1);
        ConstructionMaterialTypeEntity type = createConstructionType();

        WorkItemEntity item = createWorkItem(category, workUnit, "Работа", "Praca");

        // labour price for the package
        createLabourPrice(item, currency, pkg, new BigDecimal("120.00"));

        // two priced materials of the type in the package → a real construction band:
        //   normQty(2) × [MIN(10)..MAX(30)] = [20.00 .. 60.00]
        createConstructionMaterial(type, pkg, materialUnit, currency, new BigDecimal("10.00"));
        createConstructionMaterial(type, pkg, materialUnit, currency, new BigDecimal("30.00"));
        createConstructionConsumption(item, pkg, type, materialUnit, new BigDecimal("2.0000"));
        entityManager.flush();

        mockMvc.perform(get("/api/work-items")
                        .param("query", "workCategory.id==" + category.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(item.getId().intValue()))
                // labour price surfaced (FOR-04-12b), keyed by offerPackage.id
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].offerPackageId")
                        .value(pkg.getId().intValue()))
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].labourPrice")
                        .value(120.00))
                // construction range present and non-negative: 20.00 .. 60.00
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].construction.min")
                        .value(20.00))
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].construction.max")
                        .value(60.00))
                // finishing branch absent → explicit 0..0
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].finishing.min")
                        .value(0))
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].finishing.max")
                        .value(0));
    }

    @Test
    @DisplayName("construction range is present and non-negative for a package with a priced material of the type")
    void constructionRange_presentAndNonNegative() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit();
        MeasurementUnitEntity materialUnit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage(1);
        ConstructionMaterialTypeEntity type = createConstructionType();

        WorkItemEntity item = createWorkItem(category, workUnit, "Работа", "Praca");
        // single priced material → the band collapses to a single value: 1.5 × 40 = 60.00
        createConstructionMaterial(type, pkg, materialUnit, currency, new BigDecimal("40.00"));
        createConstructionConsumption(item, pkg, type, materialUnit, new BigDecimal("1.5000"));
        entityManager.flush();

        MvcResult result = mockMvc.perform(get("/api/work-items")
                        .param("query", "workCategory.id==" + category.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].construction.min")
                        .value(60.00))
                .andExpect(jsonPath("$.content[0].packagePivot.['" + pkg.getId() + "'].construction.max")
                        .value(60.00))
                .andReturn();

        // non-negative guard on both ends of the band
        com.fasterxml.jackson.databind.JsonNode construction =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString())
                        .get("content").get(0).get("packagePivot").get(String.valueOf(pkg.getId()))
                        .get("construction");
        assertThat(construction.get("min").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(construction.get("max").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // --- Pagination over DISTINCT work items -------------------------------------------------------

    @Test
    @DisplayName("list stays paginated over DISTINCT work items: page size < row count → no split or duplication")
    void paginationOverWorkItems_noSplitNoDuplication() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity workUnit = createUnit();
        MeasurementUnitEntity materialUnit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkgA = createOfferPackage(1);
        OfferPackageEntity pkgB = createOfferPackage(2);
        ConstructionMaterialTypeEntity type = createConstructionType();

        // Materials of the type in BOTH packages, and each work item has consumption in BOTH packages,
        // so the pivot map spans multiple packages per row — yet the list must stay one row per item.
        createConstructionMaterial(type, pkgA, materialUnit, currency, new BigDecimal("10.00"));
        createConstructionMaterial(type, pkgB, materialUnit, currency, new BigDecimal("20.00"));

        int rowCount = 5;
        for (int i = 0; i < rowCount; i++) {
            WorkItemEntity item = createWorkItem(category, workUnit, "Работа " + i, "Praca " + i);
            java.util.Map<OfferPackageEntity, BigDecimal> prices = new java.util.LinkedHashMap<>();
            prices.put(pkgA, new BigDecimal((i + 1) + "0.00"));
            prices.put(pkgB, new BigDecimal((i + 1) + "5.00"));
            createLabourPrice(item, currency, prices);
            createConstructionConsumption(item, pkgA, type, materialUnit, new BigDecimal("1.0000"));
            createConstructionConsumption(item, pkgB, type, materialUnit, new BigDecimal("2.0000"));
        }
        entityManager.flush();

        String query = "workCategory.id==" + category.getId();

        MvcResult page0 = mockMvc.perform(get("/api/work-items")
                        .param("query", query)
                        .param("sort", "id,asc")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(rowCount))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        MvcResult page1 = mockMvc.perform(get("/api/work-items")
                        .param("query", query)
                        .param("sort", "id,asc")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(rowCount))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        MvcResult page2 = mockMvc.perform(get("/api/work-items")
                        .param("query", query)
                        .param("sort", "id,asc")
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(rowCount))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();

        var ids = new java.util.ArrayList<Integer>();
        ids.addAll(readContentIds(page0));
        ids.addAll(readContentIds(page1));
        ids.addAll(readContentIds(page2));

        // Exactly rowCount DISTINCT work-item ids across all pages — no row split or pivot-driven
        // duplication (the multi-package pivot does not multiply the paginated distinct rows).
        assertThat(ids).hasSize(rowCount);
        assertThat(new java.util.HashSet<>(ids)).hasSize(rowCount);
    }

    private java.util.List<Integer> readContentIds(MvcResult result) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString());
        var ids = new java.util.ArrayList<Integer>();
        for (com.fasterxml.jackson.databind.JsonNode row : root.get("content")) {
            ids.add(row.get("id").asInt());
        }
        return ids;
    }

    // --- /metadata: one pivot descriptor per seeded package ----------------------------------------

    @Test
    @DisplayName("/metadata advertises exactly one packagePivot.{id} descriptor per seeded offer package, each with the three prices")
    void metadata_advertisesOnePivotDescriptorPerSeededPackage() throws Exception {
        OfferPackageEntity pkgA = createOfferPackage(1);
        OfferPackageEntity pkgB = createOfferPackage(2);
        OfferPackageEntity pkgC = createOfferPackage(3);
        entityManager.flush();

        MvcResult result = mockMvc.perform(get("/api/work-items/metadata"))
                .andExpect(status().isOk())
                // one descriptor per seeded package, keyed by offerPackage.id
                .andExpect(jsonPath("$.fields[?(@.name == 'packagePivot." + pkgA.getId() + "')]").exists())
                .andExpect(jsonPath("$.fields[?(@.name == 'packagePivot." + pkgB.getId() + "')]").exists())
                .andExpect(jsonPath("$.fields[?(@.name == 'packagePivot." + pkgC.getId() + "')]").exists())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString());

        // Count pivot descriptors == number of seeded packages (exactly one per package).
        var pivotNames = new java.util.HashSet<String>();
        com.fasterxml.jackson.databind.JsonNode threePrices = null;
        for (com.fasterxml.jackson.databind.JsonNode field : root.get("fields")) {
            String name = field.get("name").asText();
            if (name.startsWith("packagePivot.")) {
                pivotNames.add(name);
                if (name.equals("packagePivot." + pkgA.getId())) {
                    threePrices = field.get("nested");
                }
            }
        }
        assertThat(pivotNames).containsExactlyInAnyOrder(
                "packagePivot." + pkgA.getId(),
                "packagePivot." + pkgB.getId(),
                "packagePivot." + pkgC.getId());

        // each descriptor advertises the three price sub-fields
        assertThat(threePrices).isNotNull();
        var subFields = new java.util.HashSet<String>();
        for (com.fasterxml.jackson.databind.JsonNode sub : threePrices) {
            subFields.add(sub.get("name").asText());
        }
        assertThat(subFields).containsExactlyInAnyOrder("labourPrice", "construction", "finishing");
    }
}
