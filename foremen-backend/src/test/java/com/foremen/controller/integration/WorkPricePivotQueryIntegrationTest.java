package com.foremen.controller.integration;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the {@code WorkPrice} catalog's synthetic {@code prices.{packageId}.{field}}
 * pivot key resolved by the unified {@link com.foremen.service.query.WorkPricePivotQueryResolver}
 * ("Custom_Predicate_Flow", FOR-04-12b Requirement 4 / test Requirement 8.3).
 *
 * <p>{@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL,
 * {@code @WithMockUser(roles="ADMIN")}, {@code @Transactional} rollback for isolation. Each scenario
 * persists several {@link WorkItemEntity}s each with a {@link WorkPriceEntity} aggregator owning a
 * {@code packagePrices} collection whose members carry differing per-package {@code netPrice}s, then
 * drives {@code GET /api/work-prices} with {@code ?query=} (filter) and {@code ?sort=} (sort).
 *
 * <p>Because {@link SeededOfferPackages} caches the offer-package set once for the whole Spring
 * context and this test seeds its own packages inside a rolled-back transaction, the cache is reset
 * in {@link #resetOfferPackageCache()} before each test so the resolver's id validation sees this
 * test's freshly-persisted packages rather than a stale set warmed by another test.
 *
 * <p>Assertions:
 * <ul>
 *   <li>filter {@code prices.{id}.netPrice <op> v} → {@code offerPackage.id == id AND netPrice <op> v}
 *       returns only matching WorkPrice rows;</li>
 *   <li>sort {@code prices.{id}.netPrice,dir} orders WorkPrice rows by that package via the same
 *       discriminated join;</li>
 *   <li>pagination over WorkPrice rows (more rows than page size → no split / no duplication);</li>
 *   <li>unknown {@code offerPackage.id} → {@code error.workprices.unknown.package};</li>
 *   <li>unknown segment-3 field ({@code prices.{id}.bogus}) → {@code error.query.invalid.field.path};</li>
 *   <li>the two error cases are <b>identical</b> whether supplied as a filter or as a sort (both are
 *       resolved by the same unified resolver).</li>
 * </ul>
 *
 * <p>Validates: Requirements 8.3, 4.2, 4.3, 4.4, 4.5, 4.6
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkPricePivotQueryIntegrationTest {

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
    private WorkPriceDao workPriceDao;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private CurrencyDao currencyDao;

    @Autowired
    private OfferPackageDao offerPackageDao;

    @Autowired
    private SeededOfferPackages seededOfferPackages;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Clears the {@link SeededOfferPackages} singleton cache so the resolver's id validation reloads
     * the offer packages this test persists (which live only inside the rolled-back test transaction),
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

    /**
     * Persists a {@link WorkPriceEntity} aggregator for {@code item} with one
     * {@link WorkPackagePriceEntity} per supplied {@code (offerPackage, netPrice)} pair.
     */
    private WorkPriceEntity createAggregator(WorkItemEntity item, CurrencyEntity currency,
                                             OfferPackageEntity pkg, BigDecimal netPrice) {
        WorkPriceEntity price = new WorkPriceEntity();
        price.setWorkItem(item);

        WorkPackagePriceEntity member = new WorkPackagePriceEntity();
        member.setWorkPrice(price);
        member.setOfferPackage(pkg);
        member.setCurrency(currency);
        member.setNetPrice(netPrice);
        price.getPackagePrices().add(member);

        return workPriceDao.save(price);
    }

    private WorkPriceEntity createAggregator(WorkItemEntity item, CurrencyEntity currency,
                                             OfferPackageEntity pkgA, BigDecimal netPriceA,
                                             OfferPackageEntity pkgB, BigDecimal netPriceB) {
        WorkPriceEntity price = new WorkPriceEntity();
        price.setWorkItem(item);

        WorkPackagePriceEntity memberA = new WorkPackagePriceEntity();
        memberA.setWorkPrice(price);
        memberA.setOfferPackage(pkgA);
        memberA.setCurrency(currency);
        memberA.setNetPrice(netPriceA);
        price.getPackagePrices().add(memberA);

        WorkPackagePriceEntity memberB = new WorkPackagePriceEntity();
        memberB.setWorkPrice(price);
        memberB.setOfferPackage(pkgB);
        memberB.setCurrency(currency);
        memberB.setNetPrice(netPriceB);
        price.getPackagePrices().add(memberB);

        return workPriceDao.save(price);
    }

    // --- FILTER: prices.{id}.netPrice <op> v --------------------------------------------------------

    @Test
    @DisplayName("filter prices.{id}.netPrice>v returns only WorkPrice rows whose matching-package price satisfies the predicate")
    void filterByPackagePrice_greaterThan_returnsOnlyMatchingRows() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity start = createOfferPackage(1);
        OfferPackageEntity prestige = createOfferPackage(2);

        WorkItemEntity cheap = createWorkItem(category, unit, "Дешёвая", "Tania");
        WorkItemEntity pricey = createWorkItem(category, unit, "Дорогая", "Droga");

        // cheap: START=50, PRESTIGE=200 ; pricey: START=150, PRESTIGE=300
        WorkPriceEntity cheapPrice = createAggregator(cheap, currency,
                start, new BigDecimal("50.00"), prestige, new BigDecimal("200.00"));
        WorkPriceEntity priceyPrice = createAggregator(pricey, currency,
                start, new BigDecimal("150.00"), prestige, new BigDecimal("300.00"));
        entityManager.flush();

        // filter by the START package price > 100 → only "pricey" (START=150) matches; "cheap"
        // (START=50) is excluded even though its PRESTIGE=200 > 100 (discriminated on offerPackage.id).
        mockMvc.perform(get("/api/work-prices")
                        .param("query", "prices." + start.getId() + ".netPrice>100")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + priceyPrice.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + cheapPrice.getId() + ")]").doesNotExist());
    }

    @Test
    @DisplayName("filter discriminates by offerPackage.id: same threshold on a different package selects different rows")
    void filterByPackagePrice_discriminatesByPackageId() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity start = createOfferPackage(1);
        OfferPackageEntity prestige = createOfferPackage(2);

        WorkItemEntity cheap = createWorkItem(category, unit, "Дешёвая", "Tania");
        WorkItemEntity pricey = createWorkItem(category, unit, "Дорогая", "Droga");

        // cheap: START=50, PRESTIGE=200 ; pricey: START=150, PRESTIGE=300
        WorkPriceEntity cheapPrice = createAggregator(cheap, currency,
                start, new BigDecimal("50.00"), prestige, new BigDecimal("200.00"));
        WorkPriceEntity priceyPrice = createAggregator(pricey, currency,
                start, new BigDecimal("150.00"), prestige, new BigDecimal("300.00"));
        entityManager.flush();

        // PRESTIGE price > 100 → BOTH match (cheap PRESTIGE=200, pricey PRESTIGE=300).
        mockMvc.perform(get("/api/work-prices")
                        .param("query", "prices." + prestige.getId() + ".netPrice>100")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + priceyPrice.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + cheapPrice.getId() + ")]").exists());
    }

    // --- SORT: prices.{id}.netPrice,dir -------------------------------------------------------------

    @Test
    @DisplayName("sort prices.{id}.netPrice,asc orders WorkPrice rows by that package's price via the discriminated join")
    void sortByPackagePrice_ascending_ordersByThatPackage() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity start = createOfferPackage(1);

        // three items with START prices 30, 10, 20 (unsorted insertion order)
        WorkPriceEntity p30 = createAggregator(
                createWorkItem(category, unit, "A", "A"), currency, start, new BigDecimal("30.00"));
        WorkPriceEntity p10 = createAggregator(
                createWorkItem(category, unit, "B", "B"), currency, start, new BigDecimal("10.00"));
        WorkPriceEntity p20 = createAggregator(
                createWorkItem(category, unit, "C", "C"), currency, start, new BigDecimal("20.00"));
        entityManager.flush();

        // ascending by START price → 10, 20, 30 → ids [p10, p20, p30] in that relative order
        mockMvc.perform(get("/api/work-prices")
                        .param("query", "prices." + start.getId() + ".netPrice>0")
                        .param("sort", "prices." + start.getId() + ".netPrice,asc")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(p10.getId().intValue()))
                .andExpect(jsonPath("$.content[1].id").value(p20.getId().intValue()))
                .andExpect(jsonPath("$.content[2].id").value(p30.getId().intValue()));
    }

    @Test
    @DisplayName("sort prices.{id}.netPrice,desc reverses the order")
    void sortByPackagePrice_descending_reversesOrder() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity start = createOfferPackage(1);

        WorkPriceEntity p30 = createAggregator(
                createWorkItem(category, unit, "A", "A"), currency, start, new BigDecimal("30.00"));
        WorkPriceEntity p10 = createAggregator(
                createWorkItem(category, unit, "B", "B"), currency, start, new BigDecimal("10.00"));
        WorkPriceEntity p20 = createAggregator(
                createWorkItem(category, unit, "C", "C"), currency, start, new BigDecimal("20.00"));
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices")
                        .param("query", "prices." + start.getId() + ".netPrice>0")
                        .param("sort", "prices." + start.getId() + ".netPrice,desc")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(p30.getId().intValue()))
                .andExpect(jsonPath("$.content[1].id").value(p20.getId().intValue()))
                .andExpect(jsonPath("$.content[2].id").value(p10.getId().intValue()));
    }

    // --- PAGINATION over WorkPrice rows -------------------------------------------------------------

    @Test
    @DisplayName("pagination over WorkPrice rows: page size < row count splits into pages with no split or duplicated row")
    void paginationOverWorkPriceRows_noSplitNoDuplication() throws Exception {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity start = createOfferPackage(1);
        OfferPackageEntity prestige = createOfferPackage(2);

        // 5 aggregators, each with TWO package prices (the collection join would multiply rows
        // without distinct); page size 2 → pages of 2,2,1 and totalElements 5 (rows, not price rows).
        int rowCount = 5;
        for (int i = 0; i < rowCount; i++) {
            WorkItemEntity item = createWorkItem(category, unit, "Работа " + i, "Praca " + i);
            createAggregator(item, currency,
                    start, new BigDecimal((i + 1) + "0.00"),
                    prestige, new BigDecimal((i + 1) + "5.00"));
        }
        entityManager.flush();

        String query = "prices." + start.getId() + ".netPrice>0";

        MvcResult page0 = mockMvc.perform(get("/api/work-prices")
                        .param("query", query)
                        .param("sort", "prices." + start.getId() + ".netPrice,asc")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(rowCount))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        MvcResult page1 = mockMvc.perform(get("/api/work-prices")
                        .param("query", query)
                        .param("sort", "prices." + start.getId() + ".netPrice,asc")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(rowCount))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        MvcResult page2 = mockMvc.perform(get("/api/work-prices")
                        .param("query", query)
                        .param("sort", "prices." + start.getId() + ".netPrice,asc")
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(rowCount))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andReturn();

        // Collect the ids across all pages; assert union has exactly rowCount DISTINCT ids
        // (no WorkPrice row split across pages or duplicated by the collection join).
        var ids = new java.util.ArrayList<Integer>();
        ids.addAll(readContentIds(page0));
        ids.addAll(readContentIds(page1));
        ids.addAll(readContentIds(page2));

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

    // --- ERRORS: unknown package id / unknown field, identical for filter and sort -----------------

    @Test
    @DisplayName("filter with unknown offerPackage.id → 400 error.workprices.unknown.package")
    void filterUnknownPackageId_returnsUnknownPackageError() throws Exception {
        seedOnePricedItem();

        mockMvc.perform(get("/api/work-prices")
                        .param("query", "prices.99999999.netPrice>0")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("error.workprices.unknown.package"));
    }

    @Test
    @DisplayName("sort with unknown offerPackage.id → 400 error.workprices.unknown.package (identical to filter)")
    void sortUnknownPackageId_returnsUnknownPackageError() throws Exception {
        seedOnePricedItem();

        mockMvc.perform(get("/api/work-prices")
                        .param("sort", "prices.99999999.netPrice,asc")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("error.workprices.unknown.package"));
    }

    @Test
    @DisplayName("filter with unknown segment-3 field → 400 error.query.invalid.field.path")
    void filterUnknownField_returnsInvalidFieldPathError() throws Exception {
        OfferPackageEntity pkg = seedOnePricedItem();

        MvcResult filterResult = mockMvc.perform(get("/api/work-prices")
                        .param("query", "prices." + pkg.getId() + ".bogus>0")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isBadRequest())
                .andReturn();

        // error.query.invalid.field.path is a translated key; assert 400 + that the resolved message
        // references the offending path (the raw pivot key), which the message template interpolates.
        String body = filterResult.getResponse().getContentAsString();
        assertThat(body).contains("prices." + pkg.getId() + ".bogus");
    }

    @Test
    @DisplayName("sort with unknown segment-3 field → 400, identical error to the filter path")
    void sortUnknownField_returnsSameInvalidFieldPathError() throws Exception {
        OfferPackageEntity pkg = seedOnePricedItem();

        // Filter path body
        String filterMessage = extractMessage(mockMvc.perform(get("/api/work-prices")
                        .param("query", "prices." + pkg.getId() + ".bogus>0")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isBadRequest())
                .andReturn());

        // Sort path body — must be the SAME resolved message (same unified resolver, same validation).
        String sortMessage = extractMessage(mockMvc.perform(get("/api/work-prices")
                        .param("sort", "prices." + pkg.getId() + ".bogus,asc")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertThat(sortMessage).isEqualTo(filterMessage);
    }

    private String extractMessage(MvcResult result) throws Exception {
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString());
        return root.get("message").asText();
    }

    /** Persists a single work item with one package price and flushes; returns its offer package. */
    private OfferPackageEntity seedOnePricedItem() {
        WorkCategoryEntity category = createCategory();
        MeasurementUnitEntity unit = createUnit();
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage(1);
        WorkItemEntity item = createWorkItem(category, unit, "Работа", "Praca");
        createAggregator(item, currency, pkg, new BigDecimal("100.00"));
        entityManager.flush();
        return pkg;
    }
}
