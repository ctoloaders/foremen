package com.foremen.controller.integration;

import com.foremen.dao.DeliveryCategoryDao;
import com.foremen.dao.model.DeliveryCategoryEntity;
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
 * Integration tests for DeliveryCategoryController CRUD operations.
 * <p>
 * Mirrors {@link RoomTypeControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against
 * a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
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
class DeliveryCategoryControllerIntegrationTest {

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
    private DeliveryCategoryDao deliveryCategoryDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "dc" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private DeliveryCategoryEntity createTestDeliveryCategory(String code, String nameRU, String namePL, boolean active) {
        DeliveryCategoryEntity deliveryCategory = new DeliveryCategoryEntity();
        deliveryCategory.setCode(code);
        deliveryCategory.setNameRU(nameRU);
        deliveryCategory.setNamePL(namePL);
        deliveryCategory.setActive(active);
        return deliveryCategoryDao.save(deliveryCategory);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/delivery-categories - creates a delivery category and echoes fields")
    void createDeliveryCategory_returnsCreatedDeliveryCategory() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/delivery-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Плитка",
                                    "namePL": "Płytki",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Плитка"))
                .andExpect(jsonPath("$.namePL").value("Płytki"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/delivery-categories - missing required fields returns 400")
    void createDeliveryCategory_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/delivery-categories")
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
    @DisplayName("GET /api/delivery-categories - returns paginated delivery categories with name/code/active")
    void findDeliveryCategories_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestDeliveryCategory(code, "Краски", "Farby", true);
        entityManager.flush();

        mockMvc.perform(get("/api/delivery-categories")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/delivery-categories/{id} - returns extended DTO (nameRU/namePL/code/active)")
    void findDeliveryCategoryById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        DeliveryCategoryEntity deliveryCategory = createTestDeliveryCategory(code, "Декор", "Dekor", true);
        entityManager.flush();

        mockMvc.perform(get("/api/delivery-categories/" + deliveryCategory.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deliveryCategory.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Декор"))
                .andExpect(jsonPath("$.namePL").value("Dekor"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/delivery-categories/{nonExistentId} - returns 404")
    void findDeliveryCategoryById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/delivery-categories/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/delivery-categories - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestDeliveryCategory(code, "Освещение", "Oświetlenie", true);
        entityManager.flush();

        mockMvc.perform(get("/api/delivery-categories")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Освещение")));
    }

    @Test
    @DisplayName("GET /api/delivery-categories - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestDeliveryCategory(code, "Освещение", "Oświetlenie", true);
        entityManager.flush();

        mockMvc.perform(get("/api/delivery-categories")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Oświetlenie")));
    }

    @Test
    @DisplayName("GET /api/delivery-categories - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestDeliveryCategory(code, "Двери", "Drzwi", true);
        entityManager.flush();

        mockMvc.perform(get("/api/delivery-categories")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Drzwi")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/delivery-categories/{id} - updates names/active; code is immutable")
    void updateDeliveryCategory_updatesNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        DeliveryCategoryEntity deliveryCategory = createTestDeliveryCategory(code, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — names + active only.
        mockMvc.perform(put("/api/delivery-categories/" + deliveryCategory.getId())
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
        mockMvc.perform(get("/api/delivery-categories/" + deliveryCategory.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/delivery-categories/{id} - deletes; subsequent read is 404")
    void deleteDeliveryCategory_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        DeliveryCategoryEntity deliveryCategory = createTestDeliveryCategory(code, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/delivery-categories/" + deliveryCategory.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/delivery-categories/" + deliveryCategory.getId()))
                .andExpect(status().isNotFound());
    }
}
