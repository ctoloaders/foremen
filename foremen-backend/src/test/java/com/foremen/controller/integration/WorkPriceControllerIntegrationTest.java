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
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link com.foremen.controller.WorkPriceController} CRUD operations against the
 * package-based {@code WorkPrice} aggregator model (FOR-04-12b).
 *
 * <p>{@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL,
 * {@code @WithMockUser(roles="ADMIN")}, {@code @Transactional} rollback for isolation. A
 * {@code WorkPrice} is a per-work-item aggregator that owns a {@code packagePrices} collection of
 * {@link WorkPackagePriceEntity}; each scenario first persists a {@link WorkItemEntity} (category +
 * unit), a {@link CurrencyEntity}, and an {@link OfferPackageEntity} to reference.
 *
 * <p>Covers: create/update via the {@code packagePrices} upsert list, the list DTO exposing the
 * code-keyed {@code prices} map (with {@code offerPackageId}), extended read, delete → 404, filtering
 * by {@code workItem.id}, and {@code @Positive} netPrice validation.
 *
 * <p>Validates: Requirements 5.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkPriceControllerIntegrationTest {

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
     * Clears the {@link SeededOfferPackages} singleton cache so the code-keyed prices map is built
     * from the offer packages this test persists (which live only inside the rolled-back test
     * transaction) instead of a stale set warmed by an earlier test in the shared Spring context.
     * Mirrors the reset in {@code WorkPricePivotQueryIntegrationTest}.
     */
    @BeforeEach
    void resetOfferPackageCache() {
        ReflectionTestUtils.setField(seededOfferPackages, "cached", null);
        ReflectionTestUtils.setField(seededOfferPackages, "byId", null);
    }

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private WorkItemEntity createWorkItem(String nameRU, String namePL) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        category = workCategoryDao.save(category);

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU("Квадратный метр");
        unit.setNamePL("Metr kwadratowy");
        unit.setActive(true);
        unit = measurementUnitDao.save(unit);

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

    private OfferPackageEntity createOfferPackage() {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(unique("OP"));
        pkg.setOrderNo(1);
        pkg.setNameRU("Пакет");
        pkg.setNamePL("Pakiet");
        pkg.setActive(true);
        return offerPackageDao.save(pkg);
    }

    private WorkPriceEntity createAggregator(WorkItemEntity item, OfferPackageEntity offerPackage,
                                             CurrencyEntity currency, BigDecimal netPrice) {
        WorkPriceEntity price = new WorkPriceEntity();
        price.setWorkItem(item);

        WorkPackagePriceEntity member = new WorkPackagePriceEntity();
        member.setWorkPrice(price);
        member.setOfferPackage(offerPackage);
        member.setCurrency(currency);
        member.setNetPrice(netPrice);
        price.getPackagePrices().add(member);

        return workPriceDao.save(price);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/work-prices - creates an aggregator with one package price")
    void createAggregator_returnsWorkItemAndPackagePrices() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        entityManager.flush();

        mockMvc.perform(post("/api/work-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "packagePrices": [
                                        { "offerPackageId": %d, "currencyId": %d, "netPrice": 99.90 }
                                    ]
                                }
                                """.formatted(item.getId(), pkg.getId(), currency.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.packagePrices[0].offerPackageId").value(pkg.getId()))
                .andExpect(jsonPath("$.packagePrices[0].netPrice").value(99.90));
    }

    @Test
    @DisplayName("POST /api/work-prices - netPrice <= 0 returns 400 (@Positive)")
    void createPrice_nonPositiveNetPrice_returns400() throws Exception {
        WorkItemEntity item = createWorkItem("Работа", "Praca");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        entityManager.flush();

        mockMvc.perform(post("/api/work-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "packagePrices": [
                                        { "offerPackageId": %d, "currencyId": %d, "netPrice": 0 }
                                    ]
                                }
                                """.formatted(item.getId(), pkg.getId(), currency.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/work-prices - missing workItemId returns 400")
    void createPrice_missingWorkItem_returns400() throws Exception {
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        entityManager.flush();

        mockMvc.perform(post("/api/work-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "packagePrices": [
                                        { "offerPackageId": %d, "currencyId": %d, "netPrice": 50.00 }
                                    ]
                                }
                                """.formatted(pkg.getId(), currency.getId())))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/work-prices - rows expose workItemId + workItemName + code-keyed prices map")
    void findPrices_exposesPricesMap() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        WorkPriceEntity price = createAggregator(item, pkg, currency, new BigDecimal("120.00"));
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices")
                        .header("Accept-Language", "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == " + price.getId() + ")].workItemId")
                        .value(contains(item.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + price.getId() + ")].workItemName")
                        .value(contains("Układanie płytek")))
                .andExpect(jsonPath("$.content[?(@.id == " + price.getId() + ")].prices." + pkg.getCode()
                        + ".offerPackageId").value(contains(pkg.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + price.getId() + ")].prices." + pkg.getCode()
                        + ".netPrice").value(contains(120.00)));
    }

    @Test
    @DisplayName("GET /api/work-prices - Accept-Language: ru resolves workItemName to RU")
    void findPrices_russianLocale_resolvesWorkItemNameToRU() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        WorkPriceEntity price = createAggregator(item, pkg, currency, new BigDecimal("120.00"));
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices")
                        .header("Accept-Language", "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + price.getId() + ")].workItemName")
                        .value(contains("Укладка плитки")));
    }

    @Test
    @DisplayName("GET /api/work-prices/{id} - returns extended DTO with workItemId + packagePrices")
    void findPriceById_returnsExtendedModel() throws Exception {
        WorkItemEntity item = createWorkItem("Работа", "Praca");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        WorkPriceEntity price = createAggregator(item, pkg, currency, new BigDecimal("77.50"));
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.packagePrices[0].offerPackageId").value(pkg.getId()))
                .andExpect(jsonPath("$.packagePrices[0].netPrice").value(77.50));
    }

    @Test
    @DisplayName("GET /api/work-prices/{nonExistentId} - returns 404")
    void findPriceById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/work-prices/99999"))
                .andExpect(status().isNotFound());
    }

    // --- FILTER by reference (workItem.id) ---

    @Test
    @DisplayName("GET /api/work-prices?query=workItem.id==<id> - filters by referenced work item")
    void findPrices_filterByWorkItemId() throws Exception {
        WorkItemEntity itemA = createWorkItem("Работа A", "Praca A");
        WorkItemEntity itemB = createWorkItem("Работа B", "Praca B");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        WorkPriceEntity priceA = createAggregator(itemA, pkg, currency, new BigDecimal("10.00"));
        WorkPriceEntity priceB = createAggregator(itemB, pkg, currency, new BigDecimal("20.00"));
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices")
                        .param("query", "workItem.id==" + itemA.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + priceA.getId() + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + priceB.getId() + ")]").doesNotExist());
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/work-prices/{id} - replaces the package price collection")
    void updatePrice_replacesPackagePrices() throws Exception {
        WorkItemEntity item = createWorkItem("работа", "praca");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        WorkPriceEntity price = createAggregator(item, pkg, currency, new BigDecimal("50.00"));
        entityManager.flush();

        mockMvc.perform(put("/api/work-prices/" + price.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "packagePrices": [
                                        { "offerPackageId": %d, "currencyId": %d, "netPrice": 65.00 }
                                    ]
                                }
                                """.formatted(item.getId(), pkg.getId(), currency.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.packagePrices[0].netPrice").value(65.00));

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.packagePrices[0].netPrice").value(65.00));
    }

    // --- UNIQUE CONSTRAINT (work_price_id, offer_package_id) ---

    @Test
    @DisplayName("work_package_prices - duplicate (work_price_id, offer_package_id) is rejected by the unique constraint")
    void duplicatePackagePriceForSameAggregator_isRejected() throws Exception {
        WorkItemEntity item = createWorkItem("Дубликат", "Duplikat");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        entityManager.flush();

        // Aggregator carrying TWO package prices that both reference the SAME offer package,
        // violating the unique (work_price_id, offer_package_id) constraint.
        WorkPriceEntity price = new WorkPriceEntity();
        price.setWorkItem(item);

        WorkPackagePriceEntity first = new WorkPackagePriceEntity();
        first.setWorkPrice(price);
        first.setOfferPackage(pkg);
        first.setCurrency(currency);
        first.setNetPrice(new BigDecimal("100.00"));
        price.getPackagePrices().add(first);

        WorkPackagePriceEntity duplicate = new WorkPackagePriceEntity();
        duplicate.setWorkPrice(price);
        duplicate.setOfferPackage(pkg);
        duplicate.setCurrency(currency);
        duplicate.setNetPrice(new BigDecimal("200.00"));
        price.getPackagePrices().add(duplicate);

        // The DB unique constraint uk_work_package_prices_work_offer rejects the second row. With
        // IDENTITY id generation the cascaded child INSERTs execute during save(), so the violation
        // surfaces there (Spring translates Hibernate's ConstraintViolationException into
        // DataIntegrityViolationException at the repository boundary).
        assertThatThrownBy(() -> {
            workPriceDao.save(price);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/work-prices/{id} - deletes; subsequent read is 404")
    void deletePrice_returns200ThenNotFound() throws Exception {
        WorkItemEntity item = createWorkItem("удаляемая", "usuwana");
        CurrencyEntity currency = createCurrency();
        OfferPackageEntity pkg = createOfferPackage();
        WorkPriceEntity price = createAggregator(item, pkg, currency, new BigDecimal("30.00"));
        entityManager.flush();

        mockMvc.perform(delete("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isNotFound());
    }
}
