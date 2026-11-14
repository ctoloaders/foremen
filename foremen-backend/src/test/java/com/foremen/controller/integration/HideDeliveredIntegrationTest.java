package com.foremen.controller.integration;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-00 task 11.3 - the {@code hideDelivered} flag on {@code /api/purchase-positions} and
 * {@code /api/purchase-requests}.
 *
 * <p>Setup is flushed and the persistence context cleared before reads so lazily loaded collections
 * reflect the database. Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 22.*, 9.3, 18.1.
 */
class HideDeliveredIntegrationTest extends ProcurementIntegrationTestBase {

    private static int id(Long v) {
        return Math.toIntExact(v);
    }

    // --- positions ---

    @Test
    @DisplayName("positions - hideDelivered=true drops RECEIVED only; PARTIALLY_RECEIVED and CANCELLED stay")
    void positions_hideDelivered() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity received = position(project, SourcingPath.AGREED, PositionStatus.RECEIVED);
        PurchasePositionEntity partial = position(project, SourcingPath.AGREED, PositionStatus.PARTIALLY_RECEIVED);
        PurchasePositionEntity inDelivery = position(project, SourcingPath.AGREED, PositionStatus.IN_DELIVERY);
        PurchasePositionEntity cancelled = position(project, SourcingPath.AGREED, PositionStatus.CANCELLED);
        em.flush();
        em.clear();

        mockMvc.perform(get(POSITIONS).param("size", "200").param("hideDelivered", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(received.getId())))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(partial.getId()))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(inDelivery.getId()))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(cancelled.getId()))))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @DisplayName("positions - hideDelivered absent or false keeps RECEIVED")
    void positions_defaultKeepsReceived() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity received = position(project, SourcingPath.AGREED, PositionStatus.RECEIVED);
        position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();

        mockMvc.perform(get(POSITIONS).param("size", "200"))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(received.getId()))))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(POSITIONS).param("size", "200").param("hideDelivered", "false"))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(received.getId()))))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("positions - composes with the user filter, project scope and pagination totals")
    void positions_composesWithFilterAndScope() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity plannedA = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        position(a, SourcingPath.AGREED, PositionStatus.RECEIVED);
        position(a, SourcingPath.AGREED, PositionStatus.IN_DELIVERY);
        PurchasePositionEntity plannedB = position(b, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity foreman = memberUser("FOREMAN", a);
        em.flush();
        em.clear();

        // Scope: the foreman sees A only; hideDelivered removes the RECEIVED one -> 2 left, page size 1 -> 2 pages.
        mockMvc.perform(get(POSITIONS).param("size", "1").param("hideDelivered", "true").with(as(foreman, "FOREMAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        // User filter: status==PLANNED AND not delivered AND in scope -> only plannedA.
        mockMvc.perform(get(POSITIONS).param("size", "200").param("query", "status==PLANNED")
                        .param("hideDelivered", "true").with(as(foreman, "FOREMAN")))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(plannedA.getId()))))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(plannedB.getId())))))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- requests ---

    @Test
    @DisplayName("requests - hideDelivered=true drops COMPLETED; empty and still-open requests stay")
    void requests_hideDelivered() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity completed = request(project);
        attach(position(project, SourcingPath.AGREED, PositionStatus.RECEIVED), completed);
        attach(position(project, SourcingPath.AGREED, PositionStatus.CANCELLED), completed);
        PurchaseRequestEntity mixed = request(project);
        attach(position(project, SourcingPath.AGREED, PositionStatus.RECEIVED), mixed);
        attach(position(project, SourcingPath.AGREED, PositionStatus.IN_DELIVERY), mixed);
        PurchaseRequestEntity partial = request(project);
        attach(position(project, SourcingPath.AGREED, PositionStatus.PARTIALLY_RECEIVED), partial);
        PurchaseRequestEntity empty = request(project);
        PurchaseRequestEntity cancelled = request(project);
        attach(position(project, SourcingPath.AGREED, PositionStatus.CANCELLED), cancelled);
        em.flush();
        em.clear();

        mockMvc.perform(get(REQUESTS).param("size", "200").param("hideDelivered", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(completed.getId())))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(mixed.getId()))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(partial.getId()))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(empty.getId()))))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(cancelled.getId()))))
                .andExpect(jsonPath("$.totalElements").value(4));

        mockMvc.perform(get(REQUESTS).param("size", "200"))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(completed.getId()))))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    @DisplayName("requests - hideDelivered composes with project scope")
    void requests_composesWithScope() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity completedA = request(a);
        attach(position(a, SourcingPath.AGREED, PositionStatus.RECEIVED), completedA);
        PurchaseRequestEntity openA = request(a);
        PurchaseRequestEntity openB = request(b);
        UserEntity foreman = memberUser("FOREMAN", a);
        em.flush();
        em.clear();

        mockMvc.perform(get(REQUESTS).param("size", "200").param("hideDelivered", "true").with(as(foreman, "FOREMAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id(openA.getId()))))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(completedA.getId())))))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(openB.getId())))))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
