package com.foremen.controller.integration;

import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Integration tests for read-only ResourceController and OperationController endpoints.
 * <p>
 * Validates: Requirements 5.4, 5.5, 6.4, 6.5
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ResourceOperationControllerIntegrationTest {

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

    @PersistenceContext
    private EntityManager entityManager;

    private ResourceEntity seedResource(String code, String nameRU, String namePL) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCode(code);
        resource.setNameRU(nameRU);
        resource.setNamePL(namePL);
        resource.setDescriptionRU("Описание " + code);
        resource.setDescriptionPL("Opis " + code);
        entityManager.persist(resource);
        return resource;
    }

    private OperationEntity seedOperation(String code, String nameRU, String namePL) {
        OperationEntity operation = new OperationEntity();
        operation.setCode(code);
        operation.setNameRU(nameRU);
        operation.setNamePL(namePL);
        entityManager.persist(operation);
        return operation;
    }

    // --- Resource Controller Tests ---

    @Nested
    @DisplayName("GET /api/resources")
    class ResourceListTests {

        @Test
        @DisplayName("returns paginated resources")
        void findResources_returnsPaginatedResult() throws Exception {
            seedResource("PROJECTS", "Проекты", "Projekty");
            seedResource("ROOMS", "Помещения", "Pokoje");
            entityManager.flush();

            mockMvc.perform(get("/api/resources")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(2)))
                    .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(2)));
        }

        @Test
        @DisplayName("returns empty page when no resources exist")
        void findResources_empty_returnsEmptyPage() throws Exception {
            mockMvc.perform(get("/api/resources")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }
    }

    @Nested
    @DisplayName("GET /api/resources/{id}")
    class ResourceByIdTests {

        @Test
        @DisplayName("returns single resource with locale-resolved name (default PL)")
        void findResourceById_returnsLocalizedModel() throws Exception {
            ResourceEntity resource = seedResource("ESTIMATE", "Сметы", "Kosztorysy");
            entityManager.flush();

            mockMvc.perform(get("/api/resources/" + resource.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(resource.getId()))
                    .andExpect(jsonPath("$.code").value("ESTIMATE"))
                    .andExpect(jsonPath("$.name").value("Kosztorysy"));
        }

        @Test
        @DisplayName("returns resource with RU locale when Accept-Language is ru")
        void findResourceById_ruLocale_returnsRussianName() throws Exception {
            ResourceEntity resource = seedResource("WAREHOUSE", "Склад", "Magazyn");
            entityManager.flush();

            mockMvc.perform(get("/api/resources/" + resource.getId())
                            .header("Accept-Language", "ru"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("WAREHOUSE"))
                    .andExpect(jsonPath("$.name").value("Склад"));
        }

        @Test
        @DisplayName("returns 404 for non-existent resource")
        void findResourceById_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/resources/99999"))
                    .andExpect(status().isNotFound());
        }
    }

    // --- Operation Controller Tests ---

    @Nested
    @DisplayName("GET /api/operations")
    class OperationListTests {

        @Test
        @DisplayName("returns paginated operations")
        void findOperations_returnsPaginatedResult() throws Exception {
            seedOperation("CREATE", "Создание", "Tworzenie");
            seedOperation("READ", "Чтение", "Odczyt");
            seedOperation("UPDATE", "Обновление", "Aktualizacja");
            seedOperation("DELETE", "Удаление", "Usunięcie");
            entityManager.flush();

            mockMvc.perform(get("/api/operations")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(4)))
                    .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(4)));
        }
    }

    @Nested
    @DisplayName("GET /api/operations/{id}")
    class OperationByIdTests {

        @Test
        @DisplayName("returns single operation with locale-resolved name (default PL)")
        void findOperationById_returnsLocalizedModel() throws Exception {
            OperationEntity operation = seedOperation("CREATE", "Создание", "Tworzenie");
            entityManager.flush();

            mockMvc.perform(get("/api/operations/" + operation.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(operation.getId()))
                    .andExpect(jsonPath("$.code").value("CREATE"))
                    .andExpect(jsonPath("$.name").value("Tworzenie"));
        }

        @Test
        @DisplayName("returns operation with RU locale when Accept-Language is ru")
        void findOperationById_ruLocale_returnsRussianName() throws Exception {
            OperationEntity operation = seedOperation("DELETE", "Удаление", "Usunięcie");
            entityManager.flush();

            mockMvc.perform(get("/api/operations/" + operation.getId())
                            .header("Accept-Language", "ru"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("DELETE"))
                    .andExpect(jsonPath("$.name").value("Удаление"));
        }

        @Test
        @DisplayName("returns 404 for non-existent operation")
        void findOperationById_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/operations/99999"))
                    .andExpect(status().isNotFound());
        }
    }

    // --- Verify read-only: no write endpoints ---

    @Nested
    @DisplayName("Read-only enforcement")
    class ReadOnlyEnforcement {

        @Test
        @DisplayName("POST /api/resources - returns 405 Method Not Allowed")
        void postResources_returns405() throws Exception {
            mockMvc.perform(post("/api/resources")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code": "HACK", "nameRU": "Взлом", "namePL": "Hack"}
                                    """))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("POST /api/operations - returns 405 Method Not Allowed")
        void postOperations_returns405() throws Exception {
            mockMvc.perform(post("/api/operations")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code": "HACK", "nameRU": "Взлом", "namePL": "Hack"}
                                    """))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
