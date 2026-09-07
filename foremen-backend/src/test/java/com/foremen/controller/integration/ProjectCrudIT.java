package com.foremen.controller.integration;

import com.foremen.testsupport.MockMvcSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the generic {@code Project} CRUD lifecycle (FOR-04-13, Requirement 7.6)
 * and the {@code status} list filter (Requirement 7.7).
 *
 * <p>Mirrors {@link OfferPackageControllerIntegrationTest} / {@link DeliveryCategoryControllerIntegrationTest}:
 * {@code @SpringBootTest} + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL,
 * {@code @WithMockUser(roles="ADMIN")} (ADMIN bypasses project-membership scoping per Requirement 4.3),
 * {@code @Transactional} rollback for isolation.
 *
 * <p>Projects are created through the custom transactional {@code POST /api/projects} endpoint
 * (the generic create is disabled); as ADMIN a project can be created with an empty team and no
 * client block. The tests then exercise the generic {@code GET /api/projects/{id}},
 * {@code PUT /api/projects/{id}} (base fields via {@code ProjectUpdateRequest}),
 * {@code DELETE /api/projects/{id}}, and a {@code status} filter on {@code GET /api/projects}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ProjectCrudIT {

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

    /** Per-run unique suffix so scenarios are repeatable even without rollback. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    private String uniqueName() {
        return "project-" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    /**
     * Creates a project through the custom {@code POST /api/projects} endpoint with an empty team
     * and no client block (permitted for ADMIN), returning the generated project id.
     */
    private long createProject(String name, String status) throws Exception {
        String statusLine = status == null ? "" : "\"status\": \"" + status + "\",";
        MvcResult result = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "%s",
                                    %s
                                    "members": []
                                }
                                """.formatted(name, statusLine)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    // --- READ / UPDATE / DELETE lifecycle (Requirement 7.6) ---

    @Test
    @DisplayName("GET /api/projects/{id} - returns the created project (base fields + status)")
    void readProject_returnsCreatedProject() throws Exception {
        String name = uniqueName();
        long id = createProject(name, "ACTIVE");

        mockMvc.perform(get("/api/projects/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/projects/{nonExistentId} - returns 404")
    void readProject_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/projects/99999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/projects/{id} - updates base fields and status; a subsequent read reflects them")
    void updateProject_persistsUpdatedFields() throws Exception {
        String name = uniqueName();
        long id = createProject(name, "DRAFT");

        String newName = uniqueName();
        mockMvc.perform(put("/api/projects/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "%s",
                                    "address": "ul. Testowa 1",
                                    "area": 123.45,
                                    "status": "ON_HOLD"
                                }
                                """.formatted(newName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.address").value("ul. Testowa 1"))
                .andExpect(jsonPath("$.area").value(123.45))
                .andExpect(jsonPath("$.status").value("ON_HOLD"));

        // Confirm the update persisted via a fresh read.
        mockMvc.perform(get("/api/projects/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.address").value("ul. Testowa 1"))
                .andExpect(jsonPath("$.area").value(123.45))
                .andExpect(jsonPath("$.status").value("ON_HOLD"));
    }

    @Test
    @DisplayName("DELETE /api/projects/{id} - deletes; a subsequent read is 404")
    void deleteProject_returns200ThenNotFound() throws Exception {
        long id = createProject(uniqueName(), "ACTIVE");

        mockMvc.perform(delete("/api/projects/" + id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/" + id))
                .andExpect(status().isNotFound());
    }

    // --- LIST filter on status (Requirement 7.7) ---

    @Test
    @DisplayName("GET /api/projects?query=status==ACTIVE - returns only projects whose status equals ACTIVE")
    void listWithStatusFilter_returnsOnlyMatchingProjects() throws Exception {
        String activeName = uniqueName();
        String draftName = uniqueName();
        createProject(activeName, "ACTIVE");
        createProject(draftName, "DRAFT");

        mockMvc.perform(get("/api/projects")
                        .param("page", "0")
                        .param("size", "100")
                        .param("query", "status==ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                // the ACTIVE project is present
                .andExpect(jsonPath("$.content[?(@.name == '" + activeName + "')]").exists())
                // the DRAFT project is filtered out
                .andExpect(jsonPath("$.content[?(@.name == '" + draftName + "')]").doesNotExist())
                // every returned row has status ACTIVE
                .andExpect(jsonPath("$.content[*].status", everyItem(is("ACTIVE"))));
    }
}
