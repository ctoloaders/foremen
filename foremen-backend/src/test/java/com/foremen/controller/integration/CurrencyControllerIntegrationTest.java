package com.foremen.controller.integration;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CurrencyController CRUD operations.
 * <p>
 * Mirrors {@link MeasurementUnitControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against
 * a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create → list → read → update → delete, i18n {@code name} resolution
 * ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), {@code code} immutability on
 * update, and {@code symbol} handling (echoed on create, updatable via PUT).
 * <p>
 * Validates: Requirements 1.1, 1.2, 1.4, 1.5, 5.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class CurrencyControllerIntegrationTest {

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

    /** Per-run unique code suffix so scenarios are repeatable even without rollback. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CurrencyDao currencyDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "CU" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private CurrencyEntity createTestCurrency(String code, String symbol, String nameRU, String namePL, boolean active) {
        CurrencyEntity currency = new CurrencyEntity();
        currency.setCode(code);
        currency.setSymbol(symbol);
        currency.setNameRU(nameRU);
        currency.setNamePL(namePL);
        currency.setActive(active);
        return currencyDao.save(currency);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/currencies - creates a currency and echoes fields incl. symbol")
    void createCurrency_returnsCreatedCurrency() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/currencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "symbol": "₴",
                                    "nameRU": "Гривна",
                                    "namePL": "Hrywna",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.symbol").value("₴"))
                .andExpect(jsonPath("$.nameRU").value("Гривна"))
                .andExpect(jsonPath("$.namePL").value("Hrywna"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/currencies - missing required code returns 400")
    void createCurrency_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/currencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "₴",
                                    "nameRU": "Гривна",
                                    "namePL": "Hrywna",
                                    "active": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/currencies - missing required symbol returns 400")
    void createCurrency_missingSymbol_returns400() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/currencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Гривна",
                                    "namePL": "Hrywna",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/currencies - returns paginated currencies with code/symbol/name/active")
    void findCurrencies_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestCurrency(code, "kr", "Крона", "Korona", true);
        entityManager.flush();

        mockMvc.perform(get("/api/currencies")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].symbol").value(contains("kr")))
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/currencies/{id} - returns extended DTO (code/symbol/nameRU/namePL/active)")
    void findCurrencyById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        CurrencyEntity currency = createTestCurrency(code, "₣", "Франк", "Frank", true);
        entityManager.flush();

        mockMvc.perform(get("/api/currencies/" + currency.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(currency.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.symbol").value("₣"))
                .andExpect(jsonPath("$.nameRU").value("Франк"))
                .andExpect(jsonPath("$.namePL").value("Frank"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/currencies/{nonExistentId} - returns 404")
    void findCurrencyById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/currencies/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/currencies - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestCurrency(code, "₽", "Рубль", "Rubel", true);
        entityManager.flush();

        mockMvc.perform(get("/api/currencies")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Рубль")));
    }

    @Test
    @DisplayName("GET /api/currencies - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestCurrency(code, "₽", "Рубль", "Rubel", true);
        entityManager.flush();

        mockMvc.perform(get("/api/currencies")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Rubel")));
    }

    @Test
    @DisplayName("GET /api/currencies - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestCurrency(code, "₺", "Лира", "Lira", true);
        entityManager.flush();

        mockMvc.perform(get("/api/currencies")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Lira")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/currencies/{id} - updates symbol/names/active; code is immutable")
    void updateCurrency_updatesSymbolAndNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        CurrencyEntity currency = createTestCurrency(code, "OLD", "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — symbol + names + active only.
        mockMvc.perform(put("/api/currencies/" + currency.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "NEW",
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("NEW"))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false))
                // code must remain unchanged after update (immutable).
                .andExpect(jsonPath("$.code").value(code));

        // Confirm via a fresh read that the code was not altered and symbol was updated.
        mockMvc.perform(get("/api/currencies/" + currency.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.symbol").value("NEW"))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/currencies/{id} - deletes; subsequent read is 404")
    void deleteCurrency_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        CurrencyEntity currency = createTestCurrency(code, "X", "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/currencies/" + currency.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/currencies/" + currency.getId()))
                .andExpect(status().isNotFound());
    }
}
