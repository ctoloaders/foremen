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
