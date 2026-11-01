package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;
import com.jayway.jsonpath.JsonPath;

/**
 * FOR-05-02 bug-report Round 4 — a project's rooms keep a fixed order: new rooms are appended last,
 * a room update never changes the order, {@code findByProjectId} returns rooms in that order, and the
 * order changes only through {@code PUT /api/rooms/project/{projectId}/order}, which rejects a list
 * that is not exactly the project's rooms.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
class RoomSortOrderIT {

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
    @Autowired
    private RoomTypeDao roomTypeDao;
    @Autowired
    private ProjectDao projectDao;

    @Test
    @WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
    @DisplayName("new rooms are appended last (single and bulk create)")
    void newRoomsAreAppendedLast() throws Exception {
        long projectId = persistProject();
        long typeId = persistRoomType();
        long a = createRoom(projectId, typeId, "A");
        long b = createRoom(projectId, typeId, "B");
        mockMvc.perform(post("/api/rooms/bulk").contentType(MediaType.APPLICATION_JSON).content("""
                        [{ "projectId": %d, "roomTypeId": %d, "label": "C" },
                         { "projectId": %d, "roomTypeId": %d, "label": "D" }]
                        """.formatted(projectId, typeId, projectId, typeId)))
                .andExpect(status().isOk());

        List<RoomEntity> rooms = roomDao.findByProjectId(projectId);
        assertThat(rooms).extracting(RoomEntity::getLabel).containsExactly("A", "B", "C", "D");
        assertThat(rooms).extracting(RoomEntity::getSortOrder).containsExactly(0, 1, 2, 3);
        assertThat(rooms.get(0).getId()).isEqualTo(a);
        assertThat(rooms.get(1).getId()).isEqualTo(b);
    }

    @Test
    @WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
    @DisplayName("reorder sets the order; updates do not change it")
    void reorderPersistsAndUpdatesKeepIt() throws Exception {
        long projectId = persistProject();
        long typeId = persistRoomType();
        long a = createRoom(projectId, typeId, "A");
        long b = createRoom(projectId, typeId, "B");
        long c = createRoom(projectId, typeId, "C");

        mockMvc.perform(put("/api/rooms/project/" + projectId + "/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomIds\": [%d, %d, %d]}".formatted(c, a, b)))
                .andExpect(status().isNoContent());
        assertThat(roomDao.findByProjectId(projectId)).extracting(RoomEntity::getLabel)
                .containsExactly("C", "A", "B");

        // An update of a room (label + dimension) never moves it.
        mockMvc.perform(put("/api/rooms/" + a).contentType(MediaType.APPLICATION_JSON).content("""
                        { "projectId": %d, "roomTypeId": %d, "label": "A2", "floorArea": 12.5 }
                        """.formatted(projectId, typeId)))
                .andExpect(status().isOk());
        assertThat(roomDao.findByProjectId(projectId)).extracting(RoomEntity::getLabel)
                .containsExactly("C", "A2", "B");
    }

    @Test
    @WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
    @DisplayName("reorder rejects a list that is not exactly the project's rooms")
    void reorderRejectsInvalidLists() throws Exception {
        long projectId = persistProject();
        long otherProject = persistProject();
        long typeId = persistRoomType();
        long a = createRoom(projectId, typeId, "A");
        long b = createRoom(projectId, typeId, "B");
        long foreign = createRoom(otherProject, typeId, "X");

        for (String ids : List.of("[%d]".formatted(a), "[%d, %d, %d]".formatted(a, b, foreign),
                "[%d, %d]".formatted(a, foreign), "[%d, %d]".formatted(a, a))) {
            mockMvc.perform(put("/api/rooms/project/" + projectId + "/order")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roomIds\": " + ids + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(roomDao.findByProjectId(projectId)).extracting(RoomEntity::getLabel)
                .containsExactly("A", "B");
    }

    // --- fixtures ---

    private long persistProject() {
        ProjectEntity project = new ProjectEntity();
        project.setName("order-project-" + System.nanoTime() + "-" + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.DRAFT);
        return projectDao.save(project).getId();
    }

    private long persistRoomType() {
        RoomTypeEntity type = new RoomTypeEntity();
        type.setCode("RT-" + System.nanoTime() + "-" + COUNTER.incrementAndGet());
        type.setNameRU("Тип");
        type.setNamePL("Typ");
        type.setActive(true);
        return roomTypeDao.save(type).getId();
    }

    private long createRoom(long projectId, long typeId, String label) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"projectId\": %d, \"roomTypeId\": %d, \"label\": \"%s\" }"
                                .formatted(projectId, typeId, label)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
