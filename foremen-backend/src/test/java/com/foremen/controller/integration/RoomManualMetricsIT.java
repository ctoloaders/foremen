package com.foremen.controller.integration;

import com.foremen.dao.RoomDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the manual-vs-calculated metric source stamping on room create
 * (FOR-04-14, task 9.2). Covers:
 *
 * <ul>
 *   <li>Requirement 4.1 / 7.3 — a room created with a <b>null geometry</b> and directly supplied
 *       areas/perimeter persists those exact values, each stamped {@link MeasureSource#MANUAL}.</li>
 *   <li>Requirement 4.2 / 7.4 — a room created with <b>both</b> a geometry and manual metric values
 *       persists the geometry-derived values stamped {@link MeasureSource#CALCULATED}; the supplied
 *       manual values are ignored for the five geometry-owned metrics (geometry is authoritative).</li>
 * </ul>
 *
 * <p>Mirrors {@link ProjectCrudIT}: {@code @SpringBootTest} + MockMvc against a Testcontainers
 * PostgreSQL, {@code @ActiveProfiles("integration-test")} (Liquibase disabled, Hibernate
 * {@code create-drop} builds the schema from the JPA entities), {@code @WithMockUser(roles="ADMIN")}
 * — ADMIN bypasses the ABAC matrix and project-membership scoping, so no seeded {@code ROOMS}
 * resource row is required — and {@code @Transactional} rollback for isolation. Prerequisite
 * {@code projects} and {@code room_types} rows are created programmatically via the
 * {@link EntityManager}; source flags are asserted both on the {@code GET /api/rooms/{id}} read DTO
 * ({@code {value, source}}) and directly against the persisted {@link RoomEntity} in the DB via
 * {@link RoomDao}.
 *
 * <p>Validates: Requirements 7.3, 7.4, 4.1, 4.2
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class RoomManualMetricsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the JSON converter's text value
            // into the jsonb geometry column, matching the deployed app.
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // integration-test profile disables Liquibase; Hibernate create-drop builds the schema.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    /** Per-run unique suffix so seeded reference rows never collide across runs. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomDao roomDao;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------
    // 4.1 / 7.3 : null geometry + supplied manual areas/perimeter -> MANUAL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/rooms with null geometry + manual areas/perimeter -> persisted with MANUAL sources")
    void createWithNullGeometry_persistsSuppliedValuesAsManual() throws Exception {
        long projectId = persistProject();
        long roomTypeId = persistRoomType();

        String body = """
                {
                  "projectId": %d,
                  "roomTypeId": %d,
                  "label": "Manual room",
                  "ceilingHeight": 2.70,
                  "floorArea": 25.50,
                  "wallArea": 40.00,
                  "perimeter": 20.00,
                  "doorArea": 3.20,
                  "windowArea": 2.40
                }
                """.formatted(projectId, roomTypeId);

        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                // create response carries the flat, kept manual values
                .andExpect(jsonPath("$.floorArea").value(25.50))
                .andExpect(jsonPath("$.wallArea").value(40.00))
                .andExpect(jsonPath("$.perimeter").value(20.00))
                .andExpect(jsonPath("$.doorArea").value(3.20))
                .andExpect(jsonPath("$.windowArea").value(2.40))
                .andReturn();

        long roomId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        // Read DTO exposes each metric as { value, source } with source = MANUAL.
        mockMvc.perform(get("/api/rooms/" + roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floorArea.value").value(25.50))
                .andExpect(jsonPath("$.floorArea.source").value("MANUAL"))
                .andExpect(jsonPath("$.wallArea.value").value(40.00))
                .andExpect(jsonPath("$.wallArea.source").value("MANUAL"))
                .andExpect(jsonPath("$.perimeter.value").value(20.00))
                .andExpect(jsonPath("$.perimeter.source").value("MANUAL"))
                .andExpect(jsonPath("$.doorArea.value").value(3.20))
                .andExpect(jsonPath("$.doorArea.source").value("MANUAL"))
                .andExpect(jsonPath("$.windowArea.value").value(2.40))
                .andExpect(jsonPath("$.windowArea.source").value("MANUAL"))
                .andExpect(jsonPath("$.geometry").doesNotExist());

        // In-DB assertion: the persisted entity keeps the supplied values, each stamped MANUAL,
        // and the geometry column is null.
        RoomEntity persisted = roomDao.findById(roomId).orElseThrow();
        assertThat(persisted.getGeometry()).as("no geometry drawn").isNull();

        assertThat(persisted.getFloorArea()).isEqualByComparingTo("25.50");
        assertThat(persisted.getFloorAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(persisted.getWallArea()).isEqualByComparingTo("40.00");
        assertThat(persisted.getWallAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(persisted.getPerimeter()).isEqualByComparingTo("20.00");
        assertThat(persisted.getPerimeterSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(persisted.getDoorArea()).isEqualByComparingTo("3.20");
        assertThat(persisted.getDoorAreaSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(persisted.getWindowArea()).isEqualByComparingTo("2.40");
        assertThat(persisted.getWindowAreaSource()).isEqualTo(MeasureSource.MANUAL);
    }

    // ------------------------------------------------------------------
    // 4.2 / 7.4 : geometry + manual values -> geometry-derived, CALCULATED, manual ignored
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/rooms with geometry AND manual values -> geometry-derived CALCULATED values, manual ignored")
    void createWithGeometryAndManual_usesGeometryDerivedCalculatedValues() throws Exception {
        long projectId = persistProject();
        long roomTypeId = persistRoomType();

        // A 4 x 3 axis-aligned rectangle: floorArea = 12, perimeter = 14 (shoelace / summed edges).
        // The supplied manual values are deliberately wildly different (999.xx) so a passing test
        // proves they were overwritten by the geometry-derived numbers rather than kept.
        String body = """
                {
                  "projectId": %d,
                  "roomTypeId": %d,
                  "label": "Geometry room",
                  "ceilingHeight": 3.00,
                  "geometry": {
                    "vertices": [
                      {"x": 0, "y": 0},
                      {"x": 4, "y": 0},
                      {"x": 4, "y": 3},
                      {"x": 0, "y": 3}
                    ],
                    "walls": [ {}, {}, {}, {} ]
                  },
                  "floorArea": 999.11,
                  "wallArea": 999.22,
                  "perimeter": 999.33,
                  "doorArea": 999.44,
                  "windowArea": 999.55
                }
                """.formatted(projectId, roomTypeId);

        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                // create response carries the geometry-derived (not the supplied manual) values
                .andExpect(jsonPath("$.floorArea").value(12.00))
                .andExpect(jsonPath("$.perimeter").value(14.00))
                // openings are absent -> door/window areas are zero, not the supplied 999.xx
                .andExpect(jsonPath("$.doorArea").value(0.00))
                .andExpect(jsonPath("$.windowArea").value(0.00))
                .andReturn();

        long roomId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        // Read DTO exposes each of the five metrics as CALCULATED.
        mockMvc.perform(get("/api/rooms/" + roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floorArea.value").value(12.00))
                .andExpect(jsonPath("$.floorArea.source").value("CALCULATED"))
                .andExpect(jsonPath("$.perimeter.value").value(14.00))
                .andExpect(jsonPath("$.perimeter.source").value("CALCULATED"))
                .andExpect(jsonPath("$.wallArea.source").value("CALCULATED"))
                .andExpect(jsonPath("$.doorArea.value").value(0.00))
                .andExpect(jsonPath("$.doorArea.source").value("CALCULATED"))
                .andExpect(jsonPath("$.windowArea.value").value(0.00))
                .andExpect(jsonPath("$.windowArea.source").value("CALCULATED"));

        // In-DB assertion: the geometry-derived values are persisted with CALCULATED sources; the
        // supplied 999.xx manual values are NOT present for the five geometry-owned metrics.
        RoomEntity persisted = roomDao.findById(roomId).orElseThrow();
        assertThat(persisted.getGeometry()).as("geometry persisted").isNotNull();

        assertThat(persisted.getFloorArea()).isEqualByComparingTo("12.00");
        assertThat(persisted.getFloorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(persisted.getPerimeter()).isEqualByComparingTo("14.00");
        assertThat(persisted.getPerimeterSource()).isEqualTo(MeasureSource.CALCULATED);
        // wallArea = max(0, perimeter*height - doorArea - windowArea - wallGap*height)
        //          = max(0, 14 * 3 - 0 - 0 - 0) = 42.00
        assertThat(persisted.getWallArea()).isEqualByComparingTo("42.00");
        assertThat(persisted.getWallAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(persisted.getDoorArea()).isEqualByComparingTo("0.00");
        assertThat(persisted.getDoorAreaSource()).isEqualTo(MeasureSource.CALCULATED);
        assertThat(persisted.getWindowArea()).isEqualByComparingTo("0.00");
        assertThat(persisted.getWindowAreaSource()).isEqualTo(MeasureSource.CALCULATED);

        // None of the five metrics retained the supplied manual 999.xx values.
        assertThat(persisted.getFloorArea()).isNotEqualByComparingTo("999.11");
        assertThat(persisted.getWallArea()).isNotEqualByComparingTo("999.22");
        assertThat(persisted.getPerimeter()).isNotEqualByComparingTo("999.33");
        assertThat(persisted.getDoorArea()).isNotEqualByComparingTo("999.44");
        assertThat(persisted.getWindowArea()).isNotEqualByComparingTo("999.55");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Persists (in the test transaction) a project and returns its generated id. */
    private long persistProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("room-metrics-project-" + System.nanoTime() + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.ACTIVE);
        entityManager.persist(project);
        entityManager.flush();
        return project.getId();
    }

    /** Persists (in the test transaction) an active room type and returns its generated id. */
    private long persistRoomType() {
        int n = COUNTER.incrementAndGet();
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode("RT-" + System.nanoTime() + n);
        roomType.setNameRU("Тип " + n);
        roomType.setNamePL("Typ " + n);
        roomType.setActive(true);
        entityManager.persist(roomType);
        entityManager.flush();
        return roomType.getId();
    }
}
