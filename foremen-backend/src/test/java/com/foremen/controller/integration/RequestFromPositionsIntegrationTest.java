package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.procurement.PurchaseRequestAssignedEvent;

/**
 * FOR-11-01 task 4.5 - {@code POST /api/purchase-requests/from-positions}: request creation plus
 * attach in one transaction, date synchronization, atomic rollback, BUYER 403.
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 7.1, 7.2, 7.3, 7.5.
 */
@RecordApplicationEvents
class RequestFromPositionsIntegrationTest extends ProcurementIntegrationTestBase {

    @Autowired
    private ApplicationEvents events;

    private static final String URL = REQUESTS + "/from-positions";

    private ResultActions fromPositions(String body) throws Exception {
        return mockMvc.perform(post(URL).header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("creates the request, attaches the positions and syncs plannedDeliveryDate")
    void createsAndAttaches() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        PurchasePositionEntity p1 = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity p2 = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();
        String date = today().plusDays(12).toString();

        fromPositions("{\"positionIds\": [" + p1.getId() + "," + p2.getId() + "], \"referenceNumber\": \"FP-1\","
                + " \"assignedBuyerId\": " + buyer.getId() + ", \"expectedDeliveryDate\": \"" + date + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.projectId").value(project.getId()))
                .andExpect(jsonPath("$.assignedBuyerId").value(buyer.getId()))
                .andExpect(jsonPath("$.positionCount").value(2));

        em.clear();
        PurchasePositionEntity reloaded = em.find(PurchasePositionEntity.class, p1.getId());
        assertThat(reloaded.getPurchaseRequest()).isNotNull();
        assertThat(reloaded.getPlannedDeliveryDate()).hasToString(date);
    }

    @Test
    @DisplayName("publishes exactly one PurchaseRequestAssignedEvent for the assigned buyer")
    void singleAssignmentEvent() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        PurchasePositionEntity p1 = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity p2 = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity p3 = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();

        fromPositions("{\"positionIds\": [" + p1.getId() + "," + p2.getId() + "," + p3.getId()
                + "], \"assignedBuyerId\": " + buyer.getId() + "}").andExpect(status().isOk());

        var published = events.stream(PurchaseRequestAssignedEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).buyerUserId()).isEqualTo(buyer.getId());
    }

    @Test
    @DisplayName("without a buyer no assignment event is published")
    void noBuyerNoEvent() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();

        fromPositions("{\"positionIds\": [" + p.getId() + "]}").andExpect(status().isOk());

        assertThat(events.stream(PurchaseRequestAssignedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("empty positionIds -> 400; positions of two projects -> 400")
    void validatesIds() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pa = position(a, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity pb = position(b, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();

        fromPositions("{\"positionIds\": []}").andExpect(status().isBadRequest());
        fromPositions("{}").andExpect(status().isBadRequest());
        fromPositions("{\"positionIds\": [" + pa.getId() + "," + pb.getId() + "]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an attach failure (position already in a request) rolls back the created request")
    void attachFailureRollsBack() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity free = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity taken = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        attach(taken, request(project));
        em.flush();
        em.clear();

        // This class runs inside the base's outer test transaction, so the physical rollback is not
        // observable here; it is proven by RequestFromPositionsRollbackIntegrationTest (non-transactional).
        // Checked here: the 409 surfaces and the unit is @Transactional.
        fromPositions("{\"positionIds\": [" + free.getId() + "," + taken.getId() + "]}")
                .andExpect(status().isConflict());

        assertThat(com.foremen.service.procurement.planner.RequestFromPositionsService.class
                .getMethod("create", com.foremen.controller.model.FromPositionsRequest.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("an invalid buyer rolls back with 400")
    void invalidBuyer() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity foreman = user("FOREMAN");
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();

        fromPositions("{\"positionIds\": [" + p.getId() + "], \"assignedBuyerId\": " + foreman.getId() + "}")
                .andExpect(status().isBadRequest());
        assertThat(countRequests(project)).isZero();
    }

    @Test
    @DisplayName("BUYER -> 403, MANAGER member -> 200")
    void grantMatrix() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        UserEntity manager = memberUser("MANAGER", project);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();
        String body = "{\"positionIds\": [" + p.getId() + "]}";

        mockMvc.perform(post(URL).with(as(buyer, "BUYER")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL).with(as(manager, "MANAGER")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private long countRequests(ProjectEntity project) {
        return em.createQuery("select count(r) from PurchaseRequestEntity r where r.project.id = :id", Long.class)
                .setParameter("id", project.getId()).getSingleResult();
    }
}
