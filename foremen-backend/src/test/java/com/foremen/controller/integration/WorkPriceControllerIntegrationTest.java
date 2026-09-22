package com.foremen.controller.integration;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.pricing.SeededOfferPackages;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration tests for {@link com.foremen.controller.WorkPriceController} CRUD operations against the
 * single-price {@code WorkPrice} row model (FOR-05-04, Requirement 1).
 *
 * <p>{@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL,
 * {@code @WithMockUser(roles="ADMIN")}, {@code @Transactional} rollback for isolation. A
 * {@code WorkPrice} is a per-work-item row carrying {@code (currency, netPrice)} directly; each
 * scenario first persists a {@link WorkItemEntity} (category + unit) and a {@link CurrencyEntity}.
 *
 * <p>Covers: create/update of the single price, the list DTO exposing {@code currencyCode}/
 * {@code netPrice} directly, extended read, delete → 404, filtering by {@code workItem.id}, and
 * {@code @Positive} netPrice validation.
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3
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

    private WorkPriceEntity createAggregator(WorkItemEntity item,
                                             CurrencyEntity currency, BigDecimal netPrice) {
        WorkPriceEntity price = new WorkPriceEntity();
        price.setWorkItem(item);
        price.setCurrency(currency);
        price.setNetPrice(netPrice);
        return workPriceDao.save(price);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/work-prices - creates a single-price row")
    void createAggregator_returnsWorkItemAndNetPrice() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        entityManager.flush();

        mockMvc.perform(post("/api/work-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "currencyId": %d,
                                    "netPrice": 99.90
                                }
                                """.formatted(item.getId(), currency.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.currencyId").value(currency.getId()))
                .andExpect(jsonPath("$.netPrice").value(99.90));
    }

    @Test
    @DisplayName("POST /api/work-prices - netPrice <= 0 returns 400 (@Positive)")
    void createPrice_nonPositiveNetPrice_returns400() throws Exception {
        WorkItemEntity item = createWorkItem("Работа", "Praca");
        CurrencyEntity currency = createCurrency();
        entityManager.flush();

        mockMvc.perform(post("/api/work-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "currencyId": %d,
                                    "netPrice": 0
                                }
                                """.formatted(item.getId(), currency.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/work-prices - missing workItemId returns 400")
    void createPrice_missingWorkItem_returns400() throws Exception {
        CurrencyEntity currency = createCurrency();
        entityManager.flush();

        mockMvc.perform(post("/api/work-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "currencyId": %d,
                                    "netPrice": 50.00
                                }
                                """.formatted(currency.getId())))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/work-prices - rows expose workItemId + workItemName + currencyCode/netPrice")
    void findPrices_exposesSinglePrice() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createAggregator(item, currency, new BigDecimal("120.00"));
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
                .andExpect(jsonPath("$.content[?(@.id == " + price.getId() + ")].currencyCode")
                        .value(contains(currency.getCode())))
                .andExpect(jsonPath("$.content[?(@.id == " + price.getId() + ")].netPrice")
                        .value(contains(120.00)));
    }

    @Test
    @DisplayName("GET /api/work-prices - Accept-Language: ru resolves workItemName to RU")
    void findPrices_russianLocale_resolvesWorkItemNameToRU() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createAggregator(item, currency, new BigDecimal("120.00"));
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
    @DisplayName("GET /api/work-prices/{id} - returns extended DTO with workItemId + currencyId + netPrice")
    void findPriceById_returnsExtendedModel() throws Exception {
        WorkItemEntity item = createWorkItem("Работа", "Praca");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createAggregator(item, currency, new BigDecimal("77.50"));
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.currencyId").value(currency.getId()))
                .andExpect(jsonPath("$.netPrice").value(77.50));
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
        WorkPriceEntity priceA = createAggregator(itemA, currency, new BigDecimal("10.00"));
        WorkPriceEntity priceB = createAggregator(itemB, currency, new BigDecimal("20.00"));
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
    @DisplayName("PUT /api/work-prices/{id} - replaces the single price")
    void updatePrice_replacesNetPrice() throws Exception {
        WorkItemEntity item = createWorkItem("работа", "praca");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createAggregator(item, currency, new BigDecimal("50.00"));
        entityManager.flush();

        mockMvc.perform(put("/api/work-prices/" + price.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "currencyId": %d,
                                    "netPrice": 65.00
                                }
                                """.formatted(item.getId(), currency.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.netPrice").value(65.00));

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.netPrice").value(65.00));
    }

    // --- UNIQUE CONSTRAINT (work_item_id) ---

    @Test
    @DisplayName("work_prices - duplicate work_item_id is rejected by the unique constraint")
    void duplicateWorkPriceForSameWorkItem_isRejected() throws Exception {
        WorkItemEntity item = createWorkItem("Дубликат", "Duplikat");
        CurrencyEntity currency = createCurrency();
        entityManager.flush();

        createAggregator(item, currency, new BigDecimal("100.00"));
        entityManager.flush();

        // A second WorkPrice row referencing the SAME work item violates the unique work_item_id
        // constraint.
        WorkPriceEntity duplicate = new WorkPriceEntity();
        duplicate.setWorkItem(item);
        duplicate.setCurrency(currency);
        duplicate.setNetPrice(new BigDecimal("200.00"));

        assertThatThrownBy(() -> {
            workPriceDao.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/work-prices/{id} - deletes; subsequent read is 404")
    void deletePrice_returns200ThenNotFound() throws Exception {
        WorkItemEntity item = createWorkItem("удаляемая", "usuwana");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createAggregator(item, currency, new BigDecimal("30.00"));
        entityManager.flush();

        mockMvc.perform(delete("/api/work-prices/" + price.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isNotFound());
    }
}
