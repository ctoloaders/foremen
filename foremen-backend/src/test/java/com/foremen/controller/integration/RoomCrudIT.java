package com.foremen.controller.integration;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the generic {@code Room} CRUD lifecycle (FOR-04-14, Requirement 7.6) and the
 * reference list filters on {@code project.id} and {@code roomType.id} (Requirements 7.7, 5.7).
 *
 * <p>Mirrors {@link ProjectCrudIT} / {@link RoomTypeControllerIntegrationTest}: {@code @SpringBootTest}
 * + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL,
 * {@code @WithMockUser(roles="ADMIN")} (ADMIN bypasses project-membership scoping per Requirement 5.4),
 * {@code @Transactional} rollback for isolation.
 *
 * <p>Rooms are created through the generic {@code POST /api/rooms} endpoint with a null geometry plus
 * directly-supplied manual metrics (kept as {@code MANUAL}). Prerequisite {@code projects} and
 * {@code room_types} rows are persisted directly through their DAOs. The tests then exercise the
 * generic {@code GET /api/rooms/{id}}, {@code PUT /api/rooms/{id}}, {@code DELETE /api/rooms/{id}},
 * and the reference filters on {@code GET /api/rooms}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class RoomCrudIT {

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

    @Autowired
    private ProjectDao projectDao;

    @Autowired
    private RoomTypeDao roomTypeDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String unique(String prefix) {
        return prefix + "-" + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private ProjectEntity createProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName(unique("project"));
        project.setStatus(ProjectStatus.ACTIVE);
        return projectDao.save(project);
    }

    private RoomTypeEntity createRoomType() {
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode(unique("rt"));
        roomType.setNameRU("Комната");
        roomType.setNamePL("Pokój");
        roomType.setActive(true);
        return roomTypeDao.save(roomType);
    }

    /**
     * Creates a room through the generic {@code POST /api/rooms} endpoint with no geometry and a
     * directly-supplied manual {@code floorArea}, returning the generated room id.
     */
    private long createRoom(long projectId, long roomTypeId, String label, String floorArea) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "projectId": %d,
                                    "roomTypeId": %d,
                                    "label": "%s",
                                    "floorArea": %s
                                }
                                """.formatted(projectId, roomTypeId, label, floorArea)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    // --- READ / UPDATE / DELETE lifecycle (Requirement 7.6) ---

    @Test
    @DisplayName("GET /api/rooms/{id} - returns the created room (references + manual metric)")
    void readRoom_returnsCreatedRoom() throws Exception {
        ProjectEntity project = createProject();
        RoomTypeEntity roomType = createRoomType();
        entityManager.flush();
        long id = createRoom(project.getId(), roomType.getId(), "Bathroom 1", "12.50");

        mockMvc.perform(get("/api/rooms/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.projectId").value(project.getId().intValue()))
                .andExpect(jsonPath("$.roomTypeId").value(roomType.getId().intValue()))
                .andExpect(jsonPath("$.label").value("Bathroom 1"))
                .andExpect(jsonPath("$.floorArea.value").value(12.50))
                .andExpect(jsonPath("$.floorArea.source").value("MANUAL"));
    }

    @Test
    @DisplayName("GET /api/rooms/{nonExistentId} - returns 404")
    void readRoom_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/rooms/99999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/rooms/{id} - updates base fields; a subsequent read reflects them")
    void updateRoom_persistsUpdatedFields() throws Exception {
        ProjectEntity project = createProject();
        RoomTypeEntity roomType = createRoomType();
        entityManager.flush();
        long id = createRoom(project.getId(), roomType.getId(), "Bathroom 1", "12.50");

        mockMvc.perform(put("/api/rooms/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "projectId": %d,
                                    "roomTypeId": %d,
                                    "label": "Bathroom 2",
                                    "ceilingHeight": 2.70,
                                    "floorArea": 20.00,
                                    "perimeter": 18.00
                                }
                                """.formatted(project.getId(), roomType.getId())))
                // The update response mirrors the flat extended service model (metrics are plain
                // numbers; the {value, source} pairing is exposed only on the read DTO).
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.label").value("Bathroom 2"))
                .andExpect(jsonPath("$.ceilingHeight").value(2.70))
                .andExpect(jsonPath("$.floorArea").value(20.00))
                .andExpect(jsonPath("$.perimeter").value(18.00));

        // Confirm the update persisted via a fresh read; the read DTO exposes value + source.
        mockMvc.perform(get("/api/rooms/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Bathroom 2"))
                .andExpect(jsonPath("$.ceilingHeight").value(2.70))
                .andExpect(jsonPath("$.floorArea.value").value(20.00))
                .andExpect(jsonPath("$.floorArea.source").value("MANUAL"))
                .andExpect(jsonPath("$.perimeter.value").value(18.00))
                .andExpect(jsonPath("$.perimeter.source").value("MANUAL"));
    }

    @Test
    @DisplayName("DELETE /api/rooms/{id} - deletes; a subsequent read is 404")
    void deleteRoom_returns200ThenNotFound() throws Exception {
        ProjectEntity project = createProject();
        RoomTypeEntity roomType = createRoomType();
        entityManager.flush();
        long id = createRoom(project.getId(), roomType.getId(), "To delete", "5.00");

        mockMvc.perform(delete("/api/rooms/" + id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/rooms/" + id))
                .andExpect(status().isNotFound());
    }

    // --- LIST reference filter on project.id (Requirements 7.7, 5.7) ---

    @Test
    @DisplayName("GET /api/rooms?query=project.id==<id> - returns only rooms of the referenced project")
    void listWithProjectIdFilter_returnsOnlyMatchingRooms() throws Exception {
        ProjectEntity projectA = createProject();
        ProjectEntity projectB = createProject();
        RoomTypeEntity roomType = createRoomType();
        entityManager.flush();
        long roomA = createRoom(projectA.getId(), roomType.getId(), "Room A", "10.00");
        long roomB = createRoom(projectB.getId(), roomType.getId(), "Room B", "11.00");

        mockMvc.perform(get("/api/rooms")
                        .param("page", "0")
                        .param("size", "100")
                        .param("query", "project.id==" + projectA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == " + roomA + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + roomB + ")]").doesNotExist());
    }

    // --- LIST reference filter on roomType.id (Requirements 7.7, 5.7) ---

    @Test
    @DisplayName("GET /api/rooms?query=roomType.id==<id> - returns only rooms of the referenced room type")
    void listWithRoomTypeIdFilter_returnsOnlyMatchingRooms() throws Exception {
        ProjectEntity project = createProject();
        RoomTypeEntity roomTypeA = createRoomType();
        RoomTypeEntity roomTypeB = createRoomType();
        entityManager.flush();
        long roomA = createRoom(project.getId(), roomTypeA.getId(), "Room A", "10.00");
        long roomB = createRoom(project.getId(), roomTypeB.getId(), "Room B", "11.00");

        mockMvc.perform(get("/api/rooms")
                        .param("page", "0")
                        .param("size", "100")
                        .param("query", "roomType.id==" + roomTypeA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.id == " + roomA + ")]").exists())
                .andExpect(jsonPath("$.content[?(@.id == " + roomB + ")]").doesNotExist());
    }
}
