package com.foremen.controller.integration;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
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
 * Testcontainers integration test for the room geometry-calculation path (FOR-04-14, Requirement
 * 7.2, plus 2.3, 2.4, 3.1 in-DB).
 *
 * <p>Mirrors {@link ProjectCrudIT} / {@link RoomTypeControllerIntegrationTest}: {@code @SpringBootTest}
 * + MockMvc against a Testcontainers PostgreSQL, {@code create-drop} DDL,
 * {@code @WithMockUser(roles="ADMIN")} (ADMIN bypasses project-membership scoping per Requirement
 * 5.4), {@code @Transactional} rollback for isolation. Prerequisite {@code projects} and
 * {@code room_types} rows are created directly through their DAOs.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code POST /api/rooms} with a valid polygon geometry persists the geometry-derived metrics
 *       with {@code CALCULATED} sources, asserted <em>in the database</em> via {@link RoomDao}
 *       (Requirements 3.1, 4.2, 7.2);</li>
 *   <li>a geometry with fewer than 3 vertices is rejected with 400 and nothing is persisted
 *       (Requirement 2.3);</li>
 *   <li>a geometry with an invalid opening is rejected with 400 and nothing is persisted
 *       (Requirement 2.4).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class RoomGeometryIT {

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

    @Autowired
    private RoomDao roomDao;

    @PersistenceContext
    private EntityManager entityManager;

    private long createProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("project-" + System.nanoTime() + COUNTER.incrementAndGet());
        return projectDao.save(project).getId();
    }

    private long createRoomType() {
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode("rt" + System.nanoTime() + COUNTER.incrementAndGet());
        roomType.setNameRU("Кухня");
        roomType.setNamePL("Kuchnia");
        roomType.setActive(true);
        return roomTypeDao.save(roomType).getId();
    }

    // --- Create with geometry → calculated metrics persisted with CALCULATED sources (7.2, 3.1) ---

    @Test
    @DisplayName("POST /api/rooms with a valid polygon geometry persists CALCULATED metrics (asserted in DB)")
    void createWithGeometry_persistsCalculatedMetricsWithCalculatedSources() throws Exception {
        long projectId = createProject();
        long roomTypeId = createRoomType();
        entityManager.flush();

        // 4 x 3 rectangle: floorArea = 12.00 (shoelace), perimeter = 14.00.
        // One DOOR opening (count 1, height 2, width 1) → doorArea = 2.00, doorCount = 1.
        // ceilingHeight = 3 → wallArea = max(0, 14*3 - 2 - 0 - 0) = 40.00.
        String body = """
                {
                    "projectId": %d,
                    "roomTypeId": %d,
                    "label": "Kitchen 1",
                    "ceilingHeight": 3,
                    "geometry": {
                        "vertices": [
                            { "x": 0, "y": 0 },
                            { "x": 4, "y": 0 },
                            { "x": 4, "y": 3 },
                            { "x": 0, "y": 3 }
                        ],
                        "walls": [
                            { "openings": [ { "type": "DOOR", "count": 1, "height": 2, "width": 1 } ] },
                            {},
                            {},
                            {}
                        ]
                    }
                }
                """.formatted(projectId, roomTypeId);

        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        long roomId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        // Assert the persisted row directly in the database (Requirement 7.2 "in-DB").
        entityManager.flush();
        entityManager.clear();
        RoomEntity persisted = roomDao.findById(roomId).orElseThrow();

        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals(0,
                        persisted.getFloorArea().compareTo(new java.math.BigDecimal("12.00")), "floorArea"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(MeasureSource.CALCULATED,
                        persisted.getFloorAreaSource(), "floorAreaSource"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(0,
                        persisted.getPerimeter().compareTo(new java.math.BigDecimal("14.00")), "perimeter"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(MeasureSource.CALCULATED,
                        persisted.getPerimeterSource(), "perimeterSource"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(0,
                        persisted.getWallArea().compareTo(new java.math.BigDecimal("40.00")), "wallArea"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(MeasureSource.CALCULATED,
                        persisted.getWallAreaSource(), "wallAreaSource"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(0,
                        persisted.getDoorArea().compareTo(new java.math.BigDecimal("2.00")), "doorArea"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(MeasureSource.CALCULATED,
                        persisted.getDoorAreaSource(), "doorAreaSource"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(0,
                        persisted.getWindowArea().compareTo(new java.math.BigDecimal("0.00")), "windowArea"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(MeasureSource.CALCULATED,
                        persisted.getWindowAreaSource(), "windowAreaSource"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(1,
                        persisted.getDoorCount(), "doorCount"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(0,
                        persisted.getWindowCount(), "windowCount")
        );
    }

    // --- Geometry with < 3 vertices rejected; nothing persisted (2.3) ---

    @Test
    @DisplayName("POST /api/rooms with a geometry of fewer than 3 vertices is rejected 400 and persists nothing")
    void createWithTooFewVertices_isRejectedAndPersistsNothing() throws Exception {
        long projectId = createProject();
        long roomTypeId = createRoomType();
        entityManager.flush();

        long before = roomDao.count();

        String body = """
                {
                    "projectId": %d,
                    "roomTypeId": %d,
                    "geometry": {
                        "vertices": [
                            { "x": 0, "y": 0 },
                            { "x": 4, "y": 0 }
                        ]
                    }
                }
                """.formatted(projectId, roomTypeId);

        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        entityManager.clear();
        org.junit.jupiter.api.Assertions.assertEquals(before, roomDao.count(),
                "no room should be persisted when the geometry is invalid");
    }

    // --- Invalid opening rejected; nothing persisted (2.4) ---

    @Test
    @DisplayName("POST /api/rooms with an invalid opening is rejected 400 and persists nothing")
    void createWithInvalidOpening_isRejectedAndPersistsNothing() throws Exception {
        long projectId = createProject();
        long roomTypeId = createRoomType();
        entityManager.flush();

        long before = roomDao.count();

        // Opening with a non-positive count is invalid (Requirement 2.4).
        String body = """
                {
                    "projectId": %d,
                    "roomTypeId": %d,
                    "geometry": {
                        "vertices": [
                            { "x": 0, "y": 0 },
                            { "x": 4, "y": 0 },
                            { "x": 4, "y": 3 }
                        ],
                        "walls": [
                            { "openings": [ { "type": "DOOR", "count": 0, "height": 2, "width": 1 } ] },
                            {},
                            {}
                        ]
                    }
                }
                """.formatted(projectId, roomTypeId);

        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        entityManager.clear();
        org.junit.jupiter.api.Assertions.assertEquals(before, roomDao.count(),
                "no room should be persisted when an opening is invalid");
    }
}
