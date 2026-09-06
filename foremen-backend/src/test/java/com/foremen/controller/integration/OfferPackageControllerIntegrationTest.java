package com.foremen.controller.integration;

import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.OfferPackageEntity;
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
 * Integration tests for OfferPackageController CRUD operations.
 * <p>
 * Mirrors {@link DeliveryStatusControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc
 * against a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create (with {@code orderNo}) → list → read → update → delete, i18n {@code name}
 * resolution ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), {@code code}
 * immutability on update, and {@code orderNo} update.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class OfferPackageControllerIntegrationTest {

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
    private OfferPackageDao offerPackageDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "op" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private OfferPackageEntity createTestPackage(String code, int orderNo, String nameRU, String namePL,
                                                 boolean active) {
        OfferPackageEntity entity = new OfferPackageEntity();
        entity.setCode(code);
        entity.setOrderNo(orderNo);
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setActive(active);
        return offerPackageDao.save(entity);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/offer-packages - creates a package and echoes fields incl. orderNo")
    void createPackage_returnsCreatedPackage() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/offer-packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "orderNo": 5,
                                    "nameRU": "Новый",
                                    "namePL": "Nowe",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.orderNo").value(5))
                .andExpect(jsonPath("$.nameRU").value("Новый"))
                .andExpect(jsonPath("$.namePL").value("Nowe"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/offer-packages - missing code returns 400")
    void createPackage_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/offer-packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "orderNo": 1,
                                    "nameRU": "имя",
                                    "namePL": "nazwa"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/offer-packages - missing orderNo returns 400")
    void createPackage_missingOrderNo_returns400() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/offer-packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "имя",
                                    "namePL": "nazwa"
                                }
                                """.formatted(code)))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/offer-packages - returns paginated packages with orderNo/name/code/active")
    void findPackages_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestPackage(code, 7, "Бюджет", "Budżet", true);
        entityManager.flush();

        mockMvc.perform(get("/api/offer-packages")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].orderNo").value(contains(7)))
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/offer-packages/{id} - returns extended DTO (orderNo/nameRU/namePL/code/active)")
    void findPackageById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        OfferPackageEntity entity = createTestPackage(code, 10, "Люкс", "Lux", true);
        entityManager.flush();

        mockMvc.perform(get("/api/offer-packages/" + entity.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entity.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.orderNo").value(10))
                .andExpect(jsonPath("$.nameRU").value("Люкс"))
                .andExpect(jsonPath("$.namePL").value("Lux"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/offer-packages/{nonExistentId} - returns 404")
    void findPackageById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/offer-packages/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/offer-packages - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestPackage(code, 11, "Норма", "Norma", true);
        entityManager.flush();

        mockMvc.perform(get("/api/offer-packages")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Норма")));
    }

    @Test
    @DisplayName("GET /api/offer-packages - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestPackage(code, 11, "Норма", "Norma", true);
        entityManager.flush();

        mockMvc.perform(get("/api/offer-packages")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Norma")));
    }

    @Test
    @DisplayName("GET /api/offer-packages - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestPackage(code, 12, "Бюджет", "Budżet", true);
        entityManager.flush();

        mockMvc.perform(get("/api/offer-packages")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Budżet")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/offer-packages/{id} - updates orderNo/names/active; code is immutable")
    void updatePackage_updatesOrderNoAndNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        OfferPackageEntity entity = createTestPackage(code, 3, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — orderNo + names + active only.
        mockMvc.perform(put("/api/offer-packages/" + entity.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "orderNo": 9,
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value(9))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false))
                // code must remain unchanged after update (immutable).
                .andExpect(jsonPath("$.code").value(code));

        // Confirm via a fresh read that the code was not altered and orderNo changed.
        mockMvc.perform(get("/api/offer-packages/" + entity.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.orderNo").value(9))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/offer-packages/{id} - deletes; subsequent read is 404")
    void deletePackage_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        OfferPackageEntity entity = createTestPackage(code, 13, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/offer-packages/" + entity.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/offer-packages/" + entity.getId()))
                .andExpect(status().isNotFound());
    }
}
