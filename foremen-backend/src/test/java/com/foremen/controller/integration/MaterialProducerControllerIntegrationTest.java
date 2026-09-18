package com.foremen.controller.integration;

import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.model.MaterialProducerEntity;
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
 * Integration tests for MaterialProducerController CRUD operations.
 * <p>
 * Mirrors {@link MaterialCategoryControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc
 * against a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create → list → read → update → delete, i18n {@code name} resolution
 * ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), and {@code code}
 * immutability on update.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class MaterialProducerControllerIntegrationTest {

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
    private MaterialProducerDao materialProducerDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "mp" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private MaterialProducerEntity createTestMaterialProducer(String code, String nameRU, String namePL, boolean active) {
        MaterialProducerEntity materialProducer = new MaterialProducerEntity();
        materialProducer.setCode(code);
        materialProducer.setNameRU(nameRU);
        materialProducer.setNamePL(namePL);
        materialProducer.setActive(active);
        return materialProducerDao.save(materialProducer);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/material-producers - creates a material producer and echoes fields")
    void createMaterialProducer_returnsCreatedMaterialProducer() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/material-producers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Egger",
                                    "namePL": "Egger",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Egger"))
                .andExpect(jsonPath("$.namePL").value("Egger"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/material-producers - missing required fields returns 400")
    void createMaterialProducer_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/material-producers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "namePL": "tylko nazwa PL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // --- READ / LIST ---

    @Test
    @DisplayName("GET /api/material-producers - returns paginated material producers with name/code/active")
    void findMaterialProducers_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestMaterialProducer(code, "Villeroy & Boch", "Villeroy & Boch", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-producers")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/material-producers/{id} - returns extended DTO (nameRU/namePL/code/active)")
    void findMaterialProducerById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        MaterialProducerEntity materialProducer = createTestMaterialProducer(code, "Grohe", "Grohe", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-producers/" + materialProducer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(materialProducer.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Grohe"))
                .andExpect(jsonPath("$.namePL").value("Grohe"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/material-producers/{nonExistentId} - returns 404")
    void findMaterialProducerById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/material-producers/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/material-producers - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestMaterialProducer(code, "Церсанит", "Cersanit", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-producers")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Церсанит")));
    }

    @Test
    @DisplayName("GET /api/material-producers - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestMaterialProducer(code, "Церсанит", "Cersanit", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-producers")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Cersanit")));
    }

    @Test
    @DisplayName("GET /api/material-producers - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestMaterialProducer(code, "Порта", "Porta", true);
        entityManager.flush();

        mockMvc.perform(get("/api/material-producers")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Porta")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/material-producers/{id} - updates names/active; code is immutable")
    void updateMaterialProducer_updatesNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        MaterialProducerEntity materialProducer = createTestMaterialProducer(code, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — names + active only.
        mockMvc.perform(put("/api/material-producers/" + materialProducer.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "новое имя",
                                    "namePL": "nowa nazwa",
                                    "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false))
                // code must remain unchanged after update (immutable).
                .andExpect(jsonPath("$.code").value(code));

        // Confirm via a fresh read that the code was not altered.
        mockMvc.perform(get("/api/material-producers/" + materialProducer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/material-producers/{id} - deletes; subsequent read is 404")
    void deleteMaterialProducer_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        MaterialProducerEntity materialProducer = createTestMaterialProducer(code, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/material-producers/" + materialProducer.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/material-producers/" + materialProducer.getId()))
                .andExpect(status().isNotFound());
    }
}
