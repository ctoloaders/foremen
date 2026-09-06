package com.foremen.controller.integration;

import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.RoomTypeEntity;
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
 * Integration tests for RoomTypeController CRUD operations.
 * <p>
 * Mirrors {@link MeasurementUnitControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against
 * a Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 * <p>
 * Covers: create → list → read → update → delete, i18n {@code name} resolution
 * ({@code Accept-Language: ru} → nameRU, {@code pl}/absent → namePL), and {@code code}
 * immutability on update.
 * <p>
 * Validates: Requirements 2.1, 2.2, 2.4, 2.5, 5.1
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class RoomTypeControllerIntegrationTest {

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
    private RoomTypeDao roomTypeDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String uniqueCode() {
        return "rt" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private RoomTypeEntity createTestRoomType(String code, String nameRU, String namePL, boolean active) {
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode(code);
        roomType.setNameRU(nameRU);
        roomType.setNamePL(namePL);
        roomType.setActive(active);
        return roomTypeDao.save(roomType);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/room-types - creates a room type and echoes fields")
    void createRoomType_returnsCreatedRoomType() throws Exception {
        String code = uniqueCode();
        mockMvc.perform(post("/api/room-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "%s",
                                    "nameRU": "Кухня",
                                    "namePL": "Kuchnia",
                                    "active": true
                                }
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Кухня"))
                .andExpect(jsonPath("$.namePL").value("Kuchnia"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /api/room-types - missing required fields returns 400")
    void createRoomType_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/room-types")
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
    @DisplayName("GET /api/room-types - returns paginated room types with name/code/active")
    void findRoomTypes_returnsPaginatedResult() throws Exception {
        String code = uniqueCode();
        createTestRoomType(code, "Гостиная", "Salon", true);
        entityManager.flush();

        mockMvc.perform(get("/api/room-types")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].active").value(contains(true)));
    }

    @Test
    @DisplayName("GET /api/room-types/{id} - returns extended DTO (nameRU/namePL/code/active)")
    void findRoomTypeById_returnsExtendedModel() throws Exception {
        String code = uniqueCode();
        RoomTypeEntity roomType = createTestRoomType(code, "Кабинет", "Biuro", true);
        entityManager.flush();

        mockMvc.perform(get("/api/room-types/" + roomType.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(roomType.getId()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("Кабинет"))
                .andExpect(jsonPath("$.namePL").value("Biuro"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /api/room-types/{nonExistentId} - returns 404")
    void findRoomTypeById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/room-types/99999"))
                .andExpect(status().isNotFound());
    }

    // --- i18n name resolution on the list endpoint ---

    @Test
    @DisplayName("GET /api/room-types - Accept-Language: ru resolves name to nameRU")
    void listWithRussianLocale_resolvesNameToRU() throws Exception {
        String code = uniqueCode();
        createTestRoomType(code, "Прихожая", "Przedpokój", true);
        entityManager.flush();

        mockMvc.perform(get("/api/room-types")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Прихожая")));
    }

    @Test
    @DisplayName("GET /api/room-types - Accept-Language: pl resolves name to namePL")
    void listWithPolishLocale_resolvesNameToPL() throws Exception {
        String code = uniqueCode();
        createTestRoomType(code, "Прихожая", "Przedpokój", true);
        entityManager.flush();

        mockMvc.perform(get("/api/room-types")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Przedpokój")));
    }

    @Test
    @DisplayName("GET /api/room-types - absent Accept-Language falls back to namePL")
    void listWithoutLocale_fallsBackToPL() throws Exception {
        String code = uniqueCode();
        createTestRoomType(code, "Ванная", "Łazienka", true);
        entityManager.flush();

        mockMvc.perform(get("/api/room-types")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.code == '" + code + "')].name").value(contains("Łazienka")));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/room-types/{id} - updates names/active; code is immutable")
    void updateRoomType_updatesNamesKeepsCode() throws Exception {
        String code = uniqueCode();
        RoomTypeEntity roomType = createTestRoomType(code, "старое имя", "stara nazwa", true);
        entityManager.flush();

        // Body has NO code field — names + active only.
        mockMvc.perform(put("/api/room-types/" + roomType.getId())
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
        mockMvc.perform(get("/api/room-types/" + roomType.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.nameRU").value("новое имя"))
                .andExpect(jsonPath("$.namePL").value("nowa nazwa"))
                .andExpect(jsonPath("$.active").value(false));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/room-types/{id} - deletes; subsequent read is 404")
    void deleteRoomType_returns200ThenNotFound() throws Exception {
        String code = uniqueCode();
        RoomTypeEntity roomType = createTestRoomType(code, "удаляемая", "usuwana", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/room-types/" + roomType.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/room-types/" + roomType.getId()))
                .andExpect(status().isNotFound());
    }
}
