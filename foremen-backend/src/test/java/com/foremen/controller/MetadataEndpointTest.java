package com.foremen.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the metadata endpoint exposed by AdminController.
 * Validates: Requirements 10.1, 10.6, 10.7
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
class MetadataEndpointTest {

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

    @Test
    @DisplayName("GET /api/roles/metadata - returns valid JSON with fields list")
    void rolesMetadata_returnsValidJsonWithFieldList() throws Exception {
        mockMvc.perform(get("/api/roles/metadata"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.fields").isArray())
                .andExpect(jsonPath("$.fields").isNotEmpty())
                .andExpect(jsonPath("$.fields[*].name").exists())
                .andExpect(jsonPath("$.fields[*].dataType").exists());
    }

    @Test
    @DisplayName("GET /api/roles/metadata - includes Cache-Control: max-age=86400 header")
    void rolesMetadata_includesCacheControlHeader() throws Exception {
        mockMvc.perform(get("/api/roles/metadata"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=86400")));
    }

    @Test
    @DisplayName("GET /api/roles/metadata - i18n fields correctly identified for RoleEntity")
    void rolesMetadata_i18nFieldsCorrectlyIdentified() throws Exception {
        // RoleEntity has nameRU/namePL → name (i18n=true), descriptionRU/descriptionPL → description (i18n=true)
        // Non-i18n fields: code, system, id, createdDate, etc.
        mockMvc.perform(get("/api/roles/metadata"))
                .andExpect(status().isOk())
                // "name" should be present with i18n=true
                .andExpect(jsonPath("$.fields[?(@.name == 'name')].i18n", hasItem(true)))
                // "description" should be present with i18n=true
                .andExpect(jsonPath("$.fields[?(@.name == 'description')].i18n", hasItem(true)))
                // "code" should be present with i18n=false
                .andExpect(jsonPath("$.fields[?(@.name == 'code')].i18n", hasItem(false)))
                // "system" should be present with i18n=false
                .andExpect(jsonPath("$.fields[?(@.name == 'system')].i18n", hasItem(false)))
                // Suffixed fields (nameRU, namePL, descriptionRU, descriptionPL) should NOT be present
                .andExpect(jsonPath("$.fields[?(@.name == 'nameRU')]").isEmpty())
                .andExpect(jsonPath("$.fields[?(@.name == 'namePL')]").isEmpty())
                .andExpect(jsonPath("$.fields[?(@.name == 'descriptionRU')]").isEmpty())
                .andExpect(jsonPath("$.fields[?(@.name == 'descriptionPL')]").isEmpty());
    }

    @Test
    @DisplayName("GET /api/users/metadata - nested fields present for entity with @ManyToOne")
    void usersMetadata_nestedFieldsPresentForManyToOne() throws Exception {
        // UserEntity has @ManyToOne RoleEntity role → should have nested fields
        mockMvc.perform(get("/api/users/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name == 'role')].nested").isNotEmpty())
                .andExpect(jsonPath("$.fields[?(@.name == 'role')].nested[*][?(@.name == 'code')]").exists())
                .andExpect(jsonPath("$.fields[?(@.name == 'role')].nested[*][?(@.name == 'name')]").exists());
    }

    @Test
    @DisplayName("GET /api/roles/metadata - correct data types for known fields")
    void rolesMetadata_correctDataTypes() throws Exception {
        mockMvc.perform(get("/api/roles/metadata"))
                .andExpect(status().isOk())
                // code is String → STRING
                .andExpect(jsonPath("$.fields[?(@.name == 'code')].dataType", hasItem("STRING")))
                // system is boolean → BOOLEAN
                .andExpect(jsonPath("$.fields[?(@.name == 'system')].dataType", hasItem("BOOLEAN")))
                // id is Long → NUMBER
                .andExpect(jsonPath("$.fields[?(@.name == 'id')].dataType", hasItem("NUMBER")))
                // createdDate is LocalDateTime → DATE
                .andExpect(jsonPath("$.fields[?(@.name == 'createdDate')].dataType", hasItem("DATE")));
    }
}
