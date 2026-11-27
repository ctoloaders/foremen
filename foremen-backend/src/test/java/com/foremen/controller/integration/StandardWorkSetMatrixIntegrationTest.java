package com.foremen.controller.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.StandardWorkSetItemDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.testsupport.MockMvcSecurityConfig;

/**
 * Integration tests for {@code PUT /api/standard-work-sets/matrix} (FOR-04-11 Round 2, task 12).
 *
 * <p>NOT {@code @Transactional} (the endpoint's own transaction is under test). Repeatability: every run
 * creates uniquely-named fixtures and removes them in {@link #cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
class StandardWorkSetMatrixIntegrationTest {

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
    private static final String URL = "/api/standard-work-sets/matrix";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StandardWorkSetItemDao setDao;
    @Autowired
    private WorkItemDao workItemDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private MeasurementUnitDao measurementUnitDao;
    @Autowired
    private RoomTypeDao roomTypeDao;
    @Autowired
    private TransactionTemplate tx;

    private WorkCategoryEntity category;
    private MeasurementUnitEntity unit;
    private WorkItemEntity workA;
    private WorkItemEntity workB;
    private RoomTypeEntity roomType;

    private String unique(String prefix) {
        return prefix + System.nanoTime() + COUNTER.incrementAndGet();
    }

    private WorkItemEntity newWork(String name) {
        WorkItemEntity w = new WorkItemEntity();
        w.setWorkCategory(category);
        w.setUnit(unit);
        w.setNameRU(name);
        w.setNamePL(name);
        w.setActive(true);
        return workItemDao.save(w);
    }

    private void createFixtures() {
        category = new WorkCategoryEntity();
        category.setCode(unique("WC"));
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        workCategoryDao.save(category);

        unit = new MeasurementUnitEntity();
        unit.setCode(unique("MU"));
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        measurementUnitDao.save(unit);

        workA = newWork("A");
        workB = newWork("B");

        roomType = new RoomTypeEntity();
        roomType.setCode(unique("RT"));
        roomType.setNameRU("Кухня");
        roomType.setNamePL("Kuchnia");
        roomType.setActive(true);
        roomType = roomTypeDao.save(roomType);
    }

    @AfterEach
    void cleanUp() {
        if (category == null) {
            return;
        }
        tx.executeWithoutResult(s -> {
            for (WorkItemEntity w : new WorkItemEntity[] {workA, workB}) {
                setDao.findByRoomTypeIdAndWorkItemId(roomType.getId(), w.getId()).ifPresent(setDao::delete);
            }
            workItemDao.deleteById(workA.getId());
            workItemDao.deleteById(workB.getId());
            roomTypeDao.deleteById(roomType.getId());
            measurementUnitDao.deleteById(unit.getId());
            workCategoryDao.deleteById(category.getId());
        });
        category = null;
    }

    private String cell(Long rt, Long work) {
        return "{\"roomTypeId\":%d,\"workItemId\":%d}".formatted(rt, work);
    }

    private String body(String add, String remove) {
        return "{\"add\":[%s],\"remove\":[%s]}".formatted(add, remove);
    }

    @Test
    @DisplayName("add and remove in one call; response lists the current pairs")
    void addAndRemoveInOneCall() throws Exception {
        createFixtures();
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(cell(roomType.getId(), workA.getId()), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.removed").value(0));

        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(cell(roomType.getId(), workB.getId()), cell(roomType.getId(), workA.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.removed").value(1))
                .andExpect(jsonPath("$.pairs[?(@.workItemId==" + workB.getId() + ")]").exists())
                .andExpect(jsonPath("$.pairs[?(@.workItemId==" + workA.getId() + ")]").doesNotExist());

        assertEquals(false, setDao.existsByRoomTypeIdAndWorkItemId(roomType.getId(), workA.getId()));
        assertEquals(true, setDao.existsByRoomTypeIdAndWorkItemId(roomType.getId(), workB.getId()));
    }

    @Test
    @DisplayName("is idempotent: existing add and missing remove are no-ops")
    void idempotent() throws Exception {
        createFixtures();
        String content = body(cell(roomType.getId(), workA.getId()), cell(roomType.getId(), workB.getId()));
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.removed").value(0));
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.removed").value(0));
        assertEquals(1, setDao.findAllPairs().stream()
                .filter(r -> r[0].equals(roomType.getId())).count());
    }

    @Test
    @DisplayName("an unknown id returns 400 and changes nothing")
    void unknownIdRejectsWholeRequest() throws Exception {
        createFixtures();
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(cell(roomType.getId(), workA.getId()) + "," + cell(roomType.getId(), -1L), "")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(cell(roomType.getId(), workA.getId()) + "," + cell(-1L, workB.getId()), "")))
                .andExpect(status().isBadRequest());
        assertEquals(false, setDao.existsByRoomTypeIdAndWorkItemId(roomType.getId(), workA.getId()));
    }

    @Test
    @DisplayName("a role without a STANDARD_WORK_SETS UPDATE grant is denied")
    @WithMockUser(username = "nobody@foremen.com", roles = "NO_GRANTS_ROLE")
    void deniedWithoutUpdate() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body("", "")))
                .andExpect(status().isForbidden());
    }
}
