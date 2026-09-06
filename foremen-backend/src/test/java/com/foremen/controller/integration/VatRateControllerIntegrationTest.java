package com.foremen.controller.integration;

import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.VatRateEntity;
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

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for VatRateController CRUD operations.
 * <p>
 * Mirrors {@link CurrencyControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against
 * a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create → list → read → update → delete, i18n {@code name} resolution
 * ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), {@code code} immutability on
 * update, and {@code rate} / {@code isDefault} handling (echoed on create, updatable via PUT).
 * <p>
 * Validates: Requirements 2.4, 5.1, and 2.2 (i18n).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class VatRateControllerIntegrationTest {

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
    private VatRateDao vatRateDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "VR" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private VatRateEntity createTestVatRate(String code, BigDecimal rate, String nameRU, String namePL,
                                            boolean isDefault, boolean active) {
        VatRateEntity vatRate = new VatRateEntity();
        vatRate.setCode(code);
        vatRate.setRate(rate);
        vatRate.setNameRU(nameRU);
        vatRate.setNamePL(namePL);
        vatRate.setDefault(isDefault);
        vatRate.setActive(active);
        return vatRateDao.save(vatRate);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/vat-rates - creates a VAT rate and echoes fields incl. rate + isDefault")
    void createVatRate_returnsCreatedVatRate() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/vat-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "rate": 12.50,
                                    "nameRU": "12.5%%",
                                    "namePL": "12.5%%",
                                    "isDefault": true,
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.rate").value(12.50))
                .andExpect(jsonPath("$.nameRU").value("12.5%"))
                .andExpect(jsonPath("$.namePL").value("12.5%"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/vat-rates - missing required code returns 400")
    void createVatRate_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/vat-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "rate": 12.50,
                                    "nameRU": "12.5%",
                                    "namePL": "12.5%",
                                    "active": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/vat-rates - missing required rate returns 400")
    void createVatRate_missingRate_returns400() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/vat-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "12.5%%",
                                    "namePL": "12.5%%",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/vat-rates - returns paginated VAT rates with code/rate/name/isDefault/active")
    void findVatRates_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestVatRate(code, new BigDecimal("7.00"), "7%", "7%", false, true);
        entityManager.flush();

        mockMvc.perform(get("/api/vat-rates")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)))
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].isDefault").value(contains(false)));
    }

    @Test
    @DisplayName("GET /api/vat-rates/{id} - returns extended DTO (code/rate/nameRU/namePL/isDefault/active)")
    void findVatRateById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        VatRateEntity vatRate = createTestVatRate(code, new BigDecimal("15.00"), "15%", "15%", true, true);
        entityManager.flush();

        mockMvc.perform(get("/api/vat-rates/" + vatRate.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vatRate.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.rate").value(15.00))
                .andExpect(jsonPath("$.nameRU").value("15%"))
                .andExpect(jsonPath("$.namePL").value("15%"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/vat-rates/{nonExistentId} - returns 404")
    void findVatRateById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/vat-rates/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/vat-rates - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestVatRate(code, new BigDecimal("3.00"), "три процента", "trzy procent", false, true);
        entityManager.flush();

        mockMvc.perform(get("/api/vat-rates")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("три процента")));
    }

    @Test
    @DisplayName("GET /api/vat-rates - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestVatRate(code, new BigDecimal("3.00"), "три процента", "trzy procent", false, true);
        entityManager.flush();

        mockMvc.perform(get("/api/vat-rates")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("trzy procent")));
    }

    @Test
    @DisplayName("GET /api/vat-rates - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestVatRate(code, new BigDecimal("4.00"), "четыре", "cztery", false, true);
        entityManager.flush();

        mockMvc.perform(get("/api/vat-rates")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("cztery")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/vat-rates/{id} - updates rate/isDefault/names/active; code is immutable")
    void updateVatRate_updatesRateAndDefaultKeepsCode() throws Exception {
        String code = uniqueCode();
        VatRateEntity vatRate = createTestVatRate(code, new BigDecimal("10.00"),
                "старое имя", "stara nazwa", false, true);
        entityManager.flush();

        // Body has NO code field — rate + isDefault + names + active only.
        mockMvc.perform(put("/api/vat-rates/" + vatRate.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "rate": 20.00,
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "isDefault": true,
                                    "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(20.00))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.active").value(false))
                // code must remain unchanged after update (immutable).
                .andExpect(jsonPath("$.code").value(code));

        // Confirm via a fresh read that the code was not altered and rate/isDefault were updated.
        mockMvc.perform(get("/api/vat-rates/" + vatRate.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.rate").value(20.00))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/vat-rates/{id} - deletes; subsequent read is 404")
    void deleteVatRate_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        VatRateEntity vatRate = createTestVatRate(code, new BigDecimal("9.00"),
                "удаляемая", "usuwana", false, true);
        entityManager.flush();

        mockMvc.perform(delete("/api/vat-rates/" + vatRate.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/vat-rates/" + vatRate.getId()))
                .andExpect(status().isNotFound());
    }
}
