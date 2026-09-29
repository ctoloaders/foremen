package com.foremen.controller.integration;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the FOR-05-05 (R10.5) Work Catalog Room_Type_Attachment endpoints on
 * {@link com.foremen.controller.WorkItemController}:
 * {@code GET /api/work-items/{id}/room-types} and {@code PUT /api/work-items/{id}/room-types}.
 *
 * <p>Mirrors {@link WorkItemControllerIntegrationTest}: {@code @SpringBootTest} + MockMvc against a
 * Testcontainers PostgreSQL, {@code create-drop} DDL, {@code @WithMockUser(roles="ADMIN")},
 * {@code @Transactional} rollback for isolation.
 *
 * <p>Covers: GET returns the attached ids; PUT replaces the set (add + remove); an empty list clears
 * it; a bad room-type id 404s; a bad work-item id 404s (GET and PUT).
 *
 * <p>Validates: Requirements 10.2, 10.3, 10.5
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class WorkItemRoomTypesControllerIntegrationTest {

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

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkItemDao workItemDao;

    @Autowired
    private WorkCategoryDao workCategoryDao;

    @Autowired
    private MeasurementUnitDao measurementUnitDao;

    @Autowired
    private RoomTypeDao roomTypeDao;

    @PersistenceContext
    private EntityManager entityManager;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private WorkItemEntity createWorkItem() {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        workCategoryDao.save(category);

        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        measurementUnitDao.save(unit);

        WorkItemEntity item = new WorkItemEntity();
        item.setWorkCategory(category);
        item.setUnit(unit);
        item.setNameRU("Работа");
        item.setNamePL("Praca");
        item.setActive(true);
        return workItemDao.save(item);
    }

    private RoomTypeEntity createRoomType(String nameRU, String namePL) {
        RoomTypeEntity roomType = new RoomTypeEntity();
        roomType.setCode(unique("RT"));
        roomType.setNameRU(nameRU);
        roomType.setNamePL(namePL);
        roomType.setActive(true);
        return roomTypeDao.save(roomType);
    }

    // --- GET ---

    @Test
    @DisplayName("GET /api/work-items/{id}/room-types - empty when the work has no attachment")
    void getRoomTypes_emptyByDefault() throws Exception {
        WorkItemEntity item = createWorkItem();
        entityManager.flush();

        mockMvc.perform(get("/api/work-items/" + item.getId() + "/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds").isArray())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/work-items/{id}/room-types - returns the attached ids")
    void getRoomTypes_returnsAttachedIds() throws Exception {
        WorkItemEntity item = createWorkItem();
        RoomTypeEntity kitchen = createRoomType("Кухня", "Kuchnia");
        RoomTypeEntity bath = createRoomType("Ванная", "Łazienka");
        item.getRoomTypes().add(kitchen);
        item.getRoomTypes().add(bath);
        workItemDao.save(item);
        entityManager.flush();

        mockMvc.perform(get("/api/work-items/" + item.getId() + "/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(2)))
                .andExpect(jsonPath("$.roomTypeIds",
                        containsInAnyOrder(kitchen.getId().intValue(), bath.getId().intValue())));
    }

    @Test
    @DisplayName("GET /api/work-items/{nonExistentId}/room-types - returns 404")
    void getRoomTypes_missingWorkItem_returns404() throws Exception {
        mockMvc.perform(get("/api/work-items/99999/room-types"))
                .andExpect(status().isNotFound());
    }

    // --- PUT ---

    @Test
    @DisplayName("PUT /api/work-items/{id}/room-types - sets the attachment and echoes the ids")
    void setRoomTypes_setsAttachment() throws Exception {
        WorkItemEntity item = createWorkItem();
        RoomTypeEntity kitchen = createRoomType("Кухня", "Kuchnia");
        RoomTypeEntity bath = createRoomType("Ванная", "Łazienka");
        entityManager.flush();

        mockMvc.perform(put("/api/work-items/" + item.getId() + "/room-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roomTypeIds": [%d, %d] }
                                """.formatted(kitchen.getId(), bath.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(2)))
                .andExpect(jsonPath("$.roomTypeIds",
                        containsInAnyOrder(kitchen.getId().intValue(), bath.getId().intValue())));

        mockMvc.perform(get("/api/work-items/" + item.getId() + "/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(2)));
    }

    @Test
    @DisplayName("PUT /api/work-items/{id}/room-types - replaces the set (add + remove)")
    void setRoomTypes_replacesSet() throws Exception {
        WorkItemEntity item = createWorkItem();
        RoomTypeEntity kitchen = createRoomType("Кухня", "Kuchnia");
        RoomTypeEntity bath = createRoomType("Ванная", "Łazienka");
        RoomTypeEntity hall = createRoomType("Прихожая", "Przedpokój");
        item.getRoomTypes().add(kitchen);
        item.getRoomTypes().add(bath);
        workItemDao.save(item);
        entityManager.flush();

        // Replace {kitchen, bath} with {bath, hall}: kitchen removed, hall added, bath retained.
        mockMvc.perform(put("/api/work-items/" + item.getId() + "/room-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roomTypeIds": [%d, %d] }
                                """.formatted(bath.getId(), hall.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", contains(bath.getId().intValue(), hall.getId().intValue())));

        mockMvc.perform(get("/api/work-items/" + item.getId() + "/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(2)))
                .andExpect(jsonPath("$.roomTypeIds",
                        containsInAnyOrder(bath.getId().intValue(), hall.getId().intValue())));
    }

    @Test
    @DisplayName("PUT /api/work-items/{id}/room-types - empty list clears the attachment")
    void setRoomTypes_emptyListClears() throws Exception {
        WorkItemEntity item = createWorkItem();
        RoomTypeEntity kitchen = createRoomType("Кухня", "Kuchnia");
        item.getRoomTypes().add(kitchen);
        workItemDao.save(item);
        entityManager.flush();

        mockMvc.perform(put("/api/work-items/" + item.getId() + "/room-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"roomTypeIds\": [] }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(0)));

        mockMvc.perform(get("/api/work-items/" + item.getId() + "/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(0)));
    }

    @Test
    @DisplayName("PUT /api/work-items/{id}/room-types - null list clears the attachment")
    void setRoomTypes_nullListClears() throws Exception {
        WorkItemEntity item = createWorkItem();
        RoomTypeEntity kitchen = createRoomType("Кухня", "Kuchnia");
        item.getRoomTypes().add(kitchen);
        workItemDao.save(item);
        entityManager.flush();

        mockMvc.perform(put("/api/work-items/" + item.getId() + "/room-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeIds", hasSize(0)));
    }

    @Test
    @DisplayName("PUT /api/work-items/{id}/room-types - a bad room-type id returns 404")
    void setRoomTypes_badRoomTypeId_returns404() throws Exception {
        WorkItemEntity item = createWorkItem();
        entityManager.flush();

        mockMvc.perform(put("/api/work-items/" + item.getId() + "/room-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"roomTypeIds\": [99999] }"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/work-items/{nonExistentId}/room-types - returns 404")
    void setRoomTypes_missingWorkItem_returns404() throws Exception {
        mockMvc.perform(put("/api/work-items/99999/room-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"roomTypeIds\": [] }"))
                .andExpect(status().isNotFound());
    }
}
