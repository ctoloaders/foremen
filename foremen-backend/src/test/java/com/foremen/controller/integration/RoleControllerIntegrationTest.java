package com.foremen.controller.integration;

import com.foremen.controller.model.*;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for RoleController CRUD operations.
 * <p>
 * Validates: Requirements 7.4, 7.5, 7.6, 7.7, 13.5, 13.6
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class RoleControllerIntegrationTest {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleDao roleDao;

    @PersistenceContext
    private EntityManager entityManager;

    private RoleEntity createTestRole(String code, String nameRU, String namePL, boolean system) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU(nameRU);
        role.setNamePL(namePL);
        role.setDescriptionRU("Описание " + code);
        role.setDescriptionPL("Opis " + code);
        role.setSystem(system);
        return roleDao.save(role);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST /api/roles - creates a new role successfully")
    void createRole_returnsCreatedRole() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "NEW_ROLE",
                                    "nameRU": "Новая роль",
                                    "namePL": "Nowa rola",
                                    "descriptionRU": "Описание новой роли",
                                    "descriptionPL": "Opis nowej roli",
                                    "system": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.code").value("NEW_ROLE"))
                .andExpect(jsonPath("$.nameRU").value("Новая роль"))
                .andExpect(jsonPath("$.namePL").value("Nowa rola"))
                .andExpect(jsonPath("$.descriptionRU").value("Описание новой роли"))
                .andExpect(jsonPath("$.descriptionPL").value("Opis nowej roli"))
                .andExpect(jsonPath("$.system").value(false));
    }

    @Test
    @DisplayName("POST /api/roles - missing required fields returns 400")
    void createRole_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "descriptionRU": "Only description provided"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/roles - duplicate code returns error")
    void createRole_duplicateCode_returnsError() throws Exception {
        createTestRole("DUPLICATE", "Дубликат", "Duplikat", false);
        entityManager.flush();

        mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "DUPLICATE",
                                    "nameRU": "Другое имя",
                                    "namePL": "Inna nazwa"
                                }
                                """))
                .andExpect(status().is4xxClientError());
    }

    // --- READ ---

    @Test
    @DisplayName("GET /api/roles - returns paginated roles")
    void findRoles_returnsPaginatedResult() throws Exception {
        createTestRole("ROLE_A", "Роль A", "Rola A", false);
        createTestRole("ROLE_B", "Роль B", "Rola B", false);
        entityManager.flush();

        mockMvc.perform(get("/api/roles")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("GET /api/roles/{id} - returns role detail")
    void findRoleById_returnsExtendedModel() throws Exception {
        RoleEntity role = createTestRole("DETAIL_ROLE", "Детальная роль", "Rola szczegółowa", false);
        entityManager.flush();

        mockMvc.perform(get("/api/roles/" + role.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(role.getId()))
                .andExpect(jsonPath("$.code").value("DETAIL_ROLE"))
                .andExpect(jsonPath("$.nameRU").value("Детальная роль"))
                .andExpect(jsonPath("$.namePL").value("Rola szczegółowa"))
                .andExpect(jsonPath("$.descriptionRU").value("Описание DETAIL_ROLE"))
                .andExpect(jsonPath("$.descriptionPL").value("Opis DETAIL_ROLE"));
    }

    @Test
    @DisplayName("GET /api/roles/{nonExistentId} - returns 404")
    void findRoleById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/roles/99999"))
                .andExpect(status().isNotFound());
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT /api/roles/{id} - updates role fields")
    void updateRole_returnsUpdatedRole() throws Exception {
        RoleEntity role = createTestRole("UPDATE_ME", "Старое имя", "Stara nazwa", false);
        entityManager.flush();

        mockMvc.perform(put("/api/roles/" + role.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nameRU": "Новое имя",
                                    "namePL": "Nowa nazwa",
                                    "descriptionRU": "Новое описание",
                                    "descriptionPL": "Nowy opis"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameRU").value("Новое имя"))
                .andExpect(jsonPath("$.namePL").value("Nowa nazwa"))
                .andExpect(jsonPath("$.descriptionRU").value("Новое описание"))
                .andExpect(jsonPath("$.descriptionPL").value("Nowy opis"));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE /api/roles/{id} (non-system) - deletes successfully")
    void deleteNonSystemRole_returns200() throws Exception {
        RoleEntity role = createTestRole("DELETE_ME", "Удаляемая", "Usuwana", false);
        entityManager.flush();

        mockMvc.perform(delete("/api/roles/" + role.getId()))
                .andExpect(status().isOk());

        // Verify role no longer exists
        mockMvc.perform(get("/api/roles/" + role.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/roles/{id} (system) - returns 403")
    void deleteSystemRole_returns403() throws Exception {
        RoleEntity systemRole = createTestRole("SYSTEM_ROLE", "Системная", "Systemowa", true);
        entityManager.flush();

        mockMvc.perform(delete("/api/roles/" + systemRole.getId()))
                .andExpect(status().isForbidden());
    }

    // --- AUDIT ---

    @Test
    @DisplayName("GET /api/roles/audit/{id} - returns audit records after create")
    void getAudit_afterCreate_returnsRecords() throws Exception {
        // Create a role via API to generate audit
        String response = mockMvc.perform(post("/api/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "code": "AUDITED_ROLE",
                                    "nameRU": "Аудируемая",
                                    "namePL": "Audytowana"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Extract ID from response
        Long roleId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response).get("id").asLong();

        entityManager.flush();

        mockMvc.perform(get("/api/roles/audit/" + roleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].operation").value("CREATE"))
                .andExpect(jsonPath("$[0].entityClass").value("RoleEntity"));
    }
}
