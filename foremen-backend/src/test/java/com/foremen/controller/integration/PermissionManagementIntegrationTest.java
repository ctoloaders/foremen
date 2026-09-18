package com.foremen.controller.integration;

import com.foremen.dao.RoleDao;
import com.foremen.dao.RoleResourceDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for permission management endpoints on RoleController.
 * <p>
 * Tests GET /api/roles/{id}/permissions and PUT /api/roles/{id}/permissions
 * including success cases, error cases, cascade delete, and uniqueness constraints.
 * <p>
 * Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 12.3, 12.4, 12.5, 13.1, 13.2, 13.3, 13.4
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class PermissionManagementIntegrationTest {

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

    @Autowired
    private RoleResourceDao roleResourceDao;

    @PersistenceContext
    private EntityManager entityManager;

    private RoleEntity createTestRole(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU("Роль " + code);
        role.setNamePL("Rola " + code);
        role.setSystem(false);
        return roleDao.save(role);
    }

    private ResourceEntity createTestResource(String code) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCode(code);
        resource.setNameRU("Ресурс " + code);
        resource.setNamePL("Zasób " + code);
        resource.setDescriptionRU("Описание " + code);
        resource.setDescriptionPL("Opis " + code);
        entityManager.persist(resource);
        return resource;
    }

    private OperationEntity createTestOperation(String code) {
        OperationEntity operation = new OperationEntity();
        operation.setCode(code);
        operation.setNameRU("Операция " + code);
        operation.setNamePL("Operacja " + code);
        entityManager.persist(operation);
        return operation;
    }

    // --- GET /api/roles/{id}/permissions ---

    @Test
    @DisplayName("GET /api/roles/{id}/permissions - returns empty permissions for new role")
    void getPermissions_emptyForNewRole() throws Exception {
        RoleEntity role = createTestRole("PERM_EMPTY");
        entityManager.flush();

        mockMvc.perform(get("/api/roles/" + role.getId() + "/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value(role.getId()))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    // --- PUT /api/roles/{id}/permissions ---

    @Test
    @DisplayName("PUT /api/roles/{id}/permissions - replaces permissions atomically")
    void replacePermissions_success() throws Exception {
        RoleEntity role = createTestRole("PERM_REPLACE");
        ResourceEntity resource = createTestResource("PROJECTS");
        OperationEntity opCreate = createTestOperation("CREATE");
        OperationEntity opRead = createTestOperation("READ");
        entityManager.flush();

        mockMvc.perform(put("/api/roles/" + role.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": [
                                        {
                                            "resourceId": %d,
                                            "operationIds": [%d, %d]
                                        }
                                    ]
                                }
                                """.formatted(resource.getId(), opCreate.getId(), opRead.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value(role.getId()))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0].resourceId").value(resource.getId()))
                .andExpect(jsonPath("$.permissions[0].resourceCode").value("PROJECTS"))
                .andExpect(jsonPath("$.permissions[0].operations.length()").value(2));
    }

    @Test
    @DisplayName("PUT /api/roles/{id}/permissions - empty operations revokes operations for resource")
    void replacePermissions_emptyOperations() throws Exception {
        RoleEntity role = createTestRole("PERM_EMPTY_OPS");
        ResourceEntity resource = createTestResource("ROOMS");
        entityManager.flush();

        mockMvc.perform(put("/api/roles/" + role.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": [
                                        {
                                            "resourceId": %d,
                                            "operationIds": []
                                        }
                                    ]
                                }
                                """.formatted(resource.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value(role.getId()))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0].resourceId").value(resource.getId()))
                .andExpect(jsonPath("$.permissions[0].operations").isEmpty());
    }

    @Test
    @DisplayName("PUT /api/roles/{nonExistentId}/permissions - returns 404")
    void replacePermissions_nonExistentRole_returns404() throws Exception {
        mockMvc.perform(put("/api/roles/99999/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": []
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/roles/{id}/permissions - non-existent resource returns 400")
    void replacePermissions_nonExistentResource_returns400() throws Exception {
        RoleEntity role = createTestRole("PERM_BAD_RES");
        entityManager.flush();

        mockMvc.perform(put("/api/roles/" + role.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": [
                                        {
                                            "resourceId": 99999,
                                            "operationIds": []
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/roles/{id}/permissions - non-existent operation returns 400")
    void replacePermissions_nonExistentOperation_returns400() throws Exception {
        RoleEntity role = createTestRole("PERM_BAD_OP");
        ResourceEntity resource = createTestResource("ESTIMATE");
        entityManager.flush();

        mockMvc.perform(put("/api/roles/" + role.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": [
                                        {
                                            "resourceId": %d,
                                            "operationIds": [99999]
                                        }
                                    ]
                                }
                                """.formatted(resource.getId())))
                .andExpect(status().isBadRequest());
    }

    // --- Cascade Delete ---

    @Test
    @DisplayName("DELETE /api/roles/{id} - cascade deletes RoleResource records")
    void deleteRole_cascadeDeletesPermissions() throws Exception {
        RoleEntity role = createTestRole("PERM_CASCADE");
        ResourceEntity resource = createTestResource("WAREHOUSE");
        OperationEntity operation = createTestOperation("DELETE");
        entityManager.flush();

        // Assign permissions to the role
        mockMvc.perform(put("/api/roles/" + role.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": [
                                        {
                                            "resourceId": %d,
                                            "operationIds": [%d]
                                        }
                                    ]
                                }
                                """.formatted(resource.getId(), operation.getId())))
                .andExpect(status().isOk());

        entityManager.flush();
        entityManager.clear();

        // Verify RoleResource records exist
        List<RoleResourceEntity> before = roleResourceDao.findAllByRoleId(role.getId());
        assertThat(before).hasSize(1);

        // Delete the role
        mockMvc.perform(delete("/api/roles/" + role.getId()))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();

        // Verify RoleResource records are removed
        List<RoleResourceEntity> after = roleResourceDao.findAllByRoleId(role.getId());
        assertThat(after).isEmpty();
    }

    // --- Uniqueness Constraint ---

    @Test
    @DisplayName("PUT /api/roles/{id}/permissions - replaces old permissions entirely (no duplicates)")
    void replacePermissions_noDuplicateRoleResourceEntries() throws Exception {
        RoleEntity role = createTestRole("PERM_UNIQUE");
        ResourceEntity resource = createTestResource("UNIQUE_RES");
        OperationEntity opCreate = createTestOperation("OP_CREATE");
        OperationEntity opUpdate = createTestOperation("OP_UPDATE");
        entityManager.flush();

        // First call: assign CREATE
        mockMvc.perform(put("/api/roles/" + role.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": [
                                        {
                                            "resourceId": %d,
                                            "operationIds": [%d]
                                        }
                                    ]
                                }
                                """.formatted(resource.getId(), opCreate.getId())))
                .andExpect(status().isOk());

        entityManager.flush();
        entityManager.clear();

        // Second call: replace with UPDATE (should not create a duplicate role-resource entry)
        mockMvc.perform(put("/api/roles/" + role.getId() + "/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "permissions": [
                                        {
                                            "resourceId": %d,
                                            "operationIds": [%d]
                                        }
                                    ]
                                }
                                """.formatted(resource.getId(), opUpdate.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0].resourceId").value(resource.getId()))
                .andExpect(jsonPath("$.permissions[0].operations.length()").value(1));

        entityManager.flush();
        entityManager.clear();

        // Verify only one RoleResource record exists for this role-resource pair
        List<RoleResourceEntity> records = roleResourceDao.findAllByRoleId(role.getId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getResource().getId()).isEqualTo(resource.getId());
    }
}
