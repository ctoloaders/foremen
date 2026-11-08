package com.foremen.controller.integration;

import com.foremen.dao.RoomDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomGeometry;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FOR-05-02 bug-report Round 5 — room dimension value formulas (#1) and the narrow-place
 * dimension (#2) through the real room CRUD endpoints. Same harness as {@link RoomManualMetricsIT}:
 * Testcontainers PostgreSQL, Hibernate create-drop, ADMIN, transactional rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class RoomDimensionFormulaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoomDao roomDao;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("create with formulas: the backend evaluates them (formula wins over the number), stores the text, stamps MANUAL")
    void createEvaluatesAndStoresFormulas() throws Exception {
        long projectId = persistProject();
        long roomTypeId = persistRoomType();

        String body = """
                {
                  "projectId": %d,
                  "roomTypeId": %d,
                  "label": "Formula room",
                  "perimeter": 1,
                  "narrowPlace": 3.5,
                  "dimensionFormulas": {
                    "perimeter": "=25+31+12",
                    "ceilingHeight": "=2,5+0,2",
                    "doorCount": "=1+2",
                    "narrowPlace": "  "
                  }
                }
                """.formatted(projectId, roomTypeId);

        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        long roomId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(get("/api/rooms/" + roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perimeter.value").value(68.00))
                .andExpect(jsonPath("$.perimeter.source").value("MANUAL"))
                .andExpect(jsonPath("$.ceilingHeight").value(2.70))
                .andExpect(jsonPath("$.doorCount").value(3))
                .andExpect(jsonPath("$.narrowPlace").value(3.50))
                .andExpect(jsonPath("$.dimensionFormulas.perimeter").value("=25+31+12"))
                .andExpect(jsonPath("$.dimensionFormulas.ceilingHeight").value("=2,5+0,2"))
                .andExpect(jsonPath("$.dimensionFormulas.doorCount").value("=1+2"))
                // a blank formula entry is dropped (narrowPlace was typed as a plain number)
                .andExpect(jsonPath("$.dimensionFormulas.narrowPlace").doesNotExist());

        RoomEntity persisted = roomDao.findById(roomId).orElseThrow();
        assertThat(persisted.getPerimeter()).isEqualByComparingTo("68.00");
        assertThat(persisted.getPerimeterSource()).isEqualTo(MeasureSource.MANUAL);
        assertThat(persisted.getNarrowPlace()).isEqualByComparingTo("3.50");
        assertThat(persisted.getDimensionFormulas()).containsOnlyKeys("perimeter", "ceilingHeight", "doorCount");
    }

    @Test
    @DisplayName("update with an empty formula map clears the stored formulas and keeps the typed numbers")
    void updateWithEmptyMapClearsFormulas() throws Exception {
        long projectId = persistProject();
        long roomTypeId = persistRoomType();
        String create = """
                { "projectId": %d, "roomTypeId": %d, "dimensionFormulas": { "perimeter": "=10+5" } }
                """.formatted(projectId, roomTypeId);
        MvcResult result = mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andReturn();
        long roomId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();

        String update = """
                { "projectId": %d, "roomTypeId": %d, "perimeter": 20, "perimeterSource": "MANUAL",
                  "dimensionFormulas": {} }
                """.formatted(projectId, roomTypeId);
        mockMvc.perform(put("/api/rooms/" + roomId)
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/rooms/" + roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perimeter.value").value(20.00))
                .andExpect(jsonPath("$.dimensionFormulas.perimeter").doesNotExist());
    }

    @Test
    @DisplayName("invalid formulas are rejected with 400 and nothing is persisted")
    void invalidFormulasRejected() throws Exception {
        long projectId = persistProject();
        long roomTypeId = persistRoomType();
        long before = roomDao.count();

        for (String formulas : new String[] {
                "{ \"perimeter\": \"=25+abc\" }",
                "{ \"doorCount\": \"=5/2\" }",          // fractional count
                "{ \"perimeter\": \"=2-5\" }",          // negative
                "{ \"label\": \"=1+1\" }"                // not a dimension
        }) {
            String body = """
                    { "projectId": %d, "roomTypeId": %d, "dimensionFormulas": %s }
                    """.formatted(projectId, roomTypeId, formulas);
            mockMvc.perform(post("/api/rooms")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(roomDao.count()).isEqualTo(before);
    }

    // ---- Round 10: geometry path keys -------------------------------------------------------

    private static final String GEOMETRY_JSON = """
            { "vertices": [ { "x": 0, "y": 0 }, { "x": 4, "y": 0 }, { "x": 4, "y": 3 }, { "x": 0, "y": 3 } ],
              "walls": [ { "openings": [ { "type": "DOOR", "count": 1, "height": 2, "width": 1 } ] }, {}, {}, {} ] }
            """;

    private MvcResult postRoom(String formulas, int expectedStatus) throws Exception {
        String body = """
                { "projectId": %d, "roomTypeId": %d, "ceilingHeight": 3, "geometry": %s,
                  "dimensionFormulas": %s }
                """.formatted(persistProject(), persistRoomType(), GEOMETRY_JSON, formulas);
        return mockMvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private RoomEntity persistedFrom(MvcResult result) throws Exception {
        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        return roomDao.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("geometry formulas: wall gap and opening width are written into the geometry, doorArea recalculated, texts stored")
    void geometryFormulasWriteValuesAndRecalculate() throws Exception {
        MvcResult result = postRoom("""
                { "geometry.walls[1].wallGap": "=0.5+0.25",
                  "geometry.walls[0].openings[0].width": " =0.4+0.5 ",
                  "geometry.walls[0].length": "=2+2",
                  "geometry.walls[0].turn": "=45*2" }
                """, 200);
        RoomEntity room = persistedFrom(result);

        assertThat(room.getGeometry().getWalls().get(1).getWallGap()).isEqualTo(0.75);
        assertThat(room.getGeometry().getWalls().get(0).getOpenings().get(0).getWidth()).isEqualTo(0.9);
        // 1 x 2.0 x 0.9
        assertThat(room.getDoorArea()).isEqualByComparingTo("1.80");
        assertThat(room.getDimensionFormulas()).containsOnlyKeys(
                "geometry.walls[1].wallGap", "geometry.walls[0].openings[0].width",
                "geometry.walls[0].length", "geometry.walls[0].turn");
        assertThat(room.getDimensionFormulas()).containsEntry("geometry.walls[0].openings[0].width", "=0.4+0.5");
        assertThat(room.getDimensionFormulas()).containsEntry("geometry.walls[0].turn", "=45*2");
    }

    @Test
    @DisplayName("geometry formulas: a fractional turn is rounded HALF_UP to a whole degree and accepted; count/height/offset/finishGap evaluate")
    void geometryTurnRoundedAndOtherLeaves() throws Exception {
        MvcResult result = postRoom("""
                { "geometry.walls[2].turn": "=12.5",
                  "geometry.walls[0].openings[0].count": "=1+1",
                  "geometry.walls[0].openings[0].height": "=2.1",
                  "geometry.walls[0].openings[0].offset": "=0.1+0.2",
                  "geometry.walls[3].finishGap": "=0.0005+0.0005" }
                """, 200);
        RoomEntity room = persistedFrom(result);

        assertThat(room.getDimensionFormulas()).containsEntry("geometry.walls[2].turn", "=12.5");
        RoomGeometry.Opening opening = room.getGeometry().getWalls().get(0).getOpenings().get(0);
        assertThat(opening.getCount()).isEqualTo(2);
        assertThat(opening.getHeight()).isEqualTo(2.1);
        assertThat(opening.getOffset()).isEqualTo(0.3);
        assertThat(room.getGeometry().getWalls().get(3).getFinishGap()).isEqualTo(0.001);
    }

    @Test
    @DisplayName("geometry formulas: indices that do not exist are dropped silently, other keys are kept")
    void geometryFormulasOutOfRangeDropped() throws Exception {
        MvcResult result = postRoom("""
                { "geometry.walls[9].wallGap": "=1+1",
                  "geometry.walls[0].openings[5].width": "=1+1",
                  "geometry.walls[1].openings[0].width": "=1+1",
                  "geometry.walls[0].turn": "=90" }
                """, 200);
        RoomEntity room = persistedFrom(result);
        assertThat(room.getDimensionFormulas()).containsOnlyKeys("geometry.walls[0].turn");
    }

    @Test
    @DisplayName("geometry formulas: invalid values and unknown paths are rejected with 400")
    void geometryFormulasInvalidRejected() throws Exception {
        for (String formulas : new String[] {
                "{ \"geometry.walls[0].openings[0].count\": \"=1.5\" }",   // fractional count
                "{ \"geometry.walls[0].openings[0].count\": \"=0\" }",     // not positive
                "{ \"geometry.walls[0].openings[0].width\": \"=0\" }",     // width must be > 0
                "{ \"geometry.walls[0].wallGap\": \"=1-2\" }",             // negative
                "{ \"geometry.walls[0].turn\": \"=361\" }",                // out of range
                "{ \"geometry.walls[0].turn\": \"=-361\" }",
                "{ \"geometry.walls[0].length\": \"=abc\" }",
                "{ \"geometry.walls[0].foo\": \"=1\" }",                   // unknown path
                "{ \"geometry.walls[x].length\": \"=1\" }",
                "{ \"geometry.walls[0].openings[0].type\": \"=1\" }"
        }) {
            postRoom(formulas, 400);
        }
    }

    private long persistProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("room-formula-project-" + System.nanoTime() + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.ACTIVE);
        entityManager.persist(project);
        entityManager.flush();
        return project.getId();
    }

    private long persistRoomType() {
        int n = COUNTER.incrementAndGet();
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode("RT-F-" + System.nanoTime() + n);
        roomType.setNameRU("Тип " + n);
        roomType.setNamePL("Typ " + n);
        roomType.setActive(true);
        entityManager.persist(roomType);
        entityManager.flush();
        return roomType.getId();
    }
}
