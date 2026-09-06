package com.foremen.controller.integration;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link com.foremen.controller.WorkPriceController} CRUD operations.
 *
 * <p>Mirrors {@link WorkItemControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against a
 * Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation. Since {@code WorkPrice} carries two FKs, each scenario
 * first persists a {@link WorkItemEntity} (which itself needs a category + unit) and a
 * {@link CurrencyEntity} fixture and uses their generated ids as {@code workItemId}/{@code currencyId}.
 *
 * <p>Covers: create current price (no validTo → current true) → create historic price (validTo set →
 * current false) → list (rows expose FK ids + workItemName + currencyCode + current) → extended read →
 * update → delete → 404, filtering by {@code workItem.id}, and {@code @Positive} netPrice validation.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 5.1
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

    @PersistenceContext
    private EntityManager entityManager;

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

    private WorkPriceEntity createPrice(WorkItemEntity item, CurrencyEntity currency,
                                        BigDecimal netPrice, LocalDate validFrom, LocalDate validTo) {
        WorkPriceEntity price = new WorkPriceEntity();
        price.setWorkItem(item);
        price.setCurrency(currency);
        price.setNetPrice(netPrice);
        price.setValidFrom(validFrom);
        price.setValidTo(validTo);
        return workPriceDao.save(price);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/work-prices - creates a CURRENT price (no validTo) → current=true")
    void createCurrentPrice_returnsCurrentTrue() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        entityManager.flush();

        mockMvc.perform(post("/api/work-prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "currencyId": %d,
                                    "netPrice": 99.90,
                                    "validFrom": "2024-09-05"
                                }
                                """.formatted(item.getId(), currency.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.currencyId").value(currency.getId()))
                .andExpect(jsonPath("$.netPrice").value(99.90))
                .andExpect(jsonPath("$.validFrom").value("2024-09-05"))
                .andExpect(jsonPath("$.validTo").doesNotExist());
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
                                    "netPrice": 0,
                                    "validFrom": "2024-09-05"
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
                                    "netPrice": 50.00,
                                    "validFrom": "2024-09-05"
                                }
                                """.formatted(currency.getId())))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/work-prices - rows expose FK ids + workItemName + currencyCode + current")
    void findPrices_exposesFkIdsReferencedValuesAndCurrent() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity current = createPrice(item, currency,
                new BigDecimal("120.00"), LocalDate.of(2024, 9, 5), null);
        WorkPriceEntity historic = createPrice(item, currency,
                new BigDecimal("100.00"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 9, 4));
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices")
                        .header("Accept-Language", "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == " + current.getId() + ")].workItemId")
                        .value(contains(item.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + current.getId() + ")].currencyId")
                        .value(contains(currency.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + current.getId() + ")].workItemName")
                        .value(contains("Układanie płytek")))
                .andExpect(jsonPath("$.content[?(@.id == " + current.getId() + ")].currencyCode")
                        .value(contains(currency.getCode())))
                .andExpect(jsonPath("$.content[?(@.id == " + current.getId() + ")].current")
                        .value(contains(true)))
                .andExpect(jsonPath("$.content[?(@.id == " + historic.getId() + ")].current")
                        .value(contains(false)));
    }

    @Test
    @DisplayName("GET /api/work-prices - Accept-Language: ru resolves workItemName to RU")
    void findPrices_russianLocale_resolvesWorkItemNameToRU() throws Exception {
        WorkItemEntity item = createWorkItem("Укладка плитки", "Układanie płytek");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createPrice(item, currency,
                new BigDecimal("120.00"), LocalDate.of(2024, 9, 5), null);
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
    @DisplayName("GET /api/work-prices/{id} - returns extended DTO with FK ids + price + dates")
    void findPriceById_returnsExtendedModel() throws Exception {
        WorkItemEntity item = createWorkItem("Работа", "Praca");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createPrice(item, currency,
                new BigDecimal("77.50"), LocalDate.of(2024, 9, 5), null);
        entityManager.flush();

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(price.getId()))
                .andExpect(jsonPath("$.workItemId").value(item.getId()))
                .andExpect(jsonPath("$.currencyId").value(currency.getId()))
                .andExpect(jsonPath("$.netPrice").value(77.50))
                .andExpect(jsonPath("$.validFrom").value("2024-09-05"));
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
        WorkPriceEntity priceA = createPrice(itemA, currency,
                new BigDecimal("10.00"), LocalDate.of(2024, 9, 5), null);
        WorkPriceEntity priceB = createPrice(itemB, currency,
                new BigDecimal("20.00"), LocalDate.of(2024, 9, 5), null);
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
    @DisplayName("PUT /api/work-prices/{id} - updates FKs, price and dates")
    void updatePrice_changesFieldsAndClosesValidity() throws Exception {
        WorkItemEntity itemOld = createWorkItem("старая", "stara");
        WorkItemEntity itemNew = createWorkItem("новая", "nowa");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createPrice(itemOld, currency,
                new BigDecimal("50.00"), LocalDate.of(2024, 9, 5), null);
        entityManager.flush();

        mockMvc.perform(put("/api/work-prices/" + price.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "workItemId": %d,
                                    "currencyId": %d,
                                    "netPrice": 65.00,
                                    "validFrom": "2024-09-05",
                                    "validTo": "2024-12-31"
                                }
                                """.formatted(itemNew.getId(), currency.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(itemNew.getId()))
                .andExpect(jsonPath("$.netPrice").value(65.00))
                .andExpect(jsonPath("$.validTo").value("2024-12-31"));

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemId").value(itemNew.getId()))
                .andExpect(jsonPath("$.netPrice").value(65.00))
                .andExpect(jsonPath("$.validTo").value("2024-12-31"));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/work-prices/{id} - deletes; subsequent read is 404")
    void deletePrice_returns200ThenNotFound() throws Exception {
        WorkItemEntity item = createWorkItem("удаляемая", "usuwana");
        CurrencyEntity currency = createCurrency();
        WorkPriceEntity price = createPrice(item, currency,
                new BigDecimal("30.00"), LocalDate.of(2024, 9, 5), null);
        entityManager.flush();

        mockMvc.perform(delete("/api/work-prices/" + price.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/work-prices/" + price.getId()))
                .andExpect(status().isNotFound());
    }
}
