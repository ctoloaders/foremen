package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-00 task 14.2 - {@code plannedDeliveryDate} synchronisation (R11.11) and
 * {@code GET /api/purchase-positions/upcoming} (R23).
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction. Dates keep a margin of several days
 * from the window edges so the UTC/Warsaw day boundary cannot flip a result.
 *
 * <p>Validates Requirements 11.11, 23.1 - 23.5.
 */
class PlannedDeliveryAndUpcomingIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String UPCOMING = POSITIONS + "/upcoming";

    private void clear() {
        em.flush();
        em.clear();
    }

    private ResultActions attachHttp(Long requestId, Long... positionIds) throws Exception {
        String ids = String.join(",", java.util.Arrays.stream(positionIds).map(String::valueOf).toList());
        return mockMvc.perform(post(REQUESTS + "/" + requestId + "/positions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"positionIds\": [" + ids + "]}"));
    }

    private LocalDate plannedDate(Long positionId) {
        em.flush();
        em.clear();
        return em.find(PurchasePositionEntity.class, positionId).getPlannedDeliveryDate();
    }

    private PurchasePositionEntity planned(ProjectEntity project, PositionStatus status, LocalDate date) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, status);
        p.setPlannedDeliveryDate(date);
        em.flush();
        return p;
    }

    // --- synchronizer ---

    @Test
    @DisplayName("attach copies the request date, date change re-applies to non-terminal only, detach clears")
    void synchronizer_lifecycle() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity request = request(project);
        request.setExpectedDeliveryDate(today().plusDays(10));
        PurchasePositionEntity open = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity received = position(project, SourcingPath.AGREED, PositionStatus.RECEIVED);
        clear();

        attachHttp(request.getId(), open.getId(), received.getId()).andExpect(status().isOk());
        assertThat(plannedDate(open.getId())).isEqualTo(today().plusDays(10));
        assertThat(plannedDate(received.getId())).isEqualTo(today().plusDays(10));

        LocalDate moved = today().plusDays(20);
        mockMvc.perform(put(REQUESTS + "/" + request.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedDeliveryDate\": \"" + moved + "\"}"))
                .andExpect(status().isOk());
        assertThat(plannedDate(open.getId())).isEqualTo(moved);
        assertThat(plannedDate(received.getId())).isEqualTo(today().plusDays(10));

        mockMvc.perform(delete(REQUESTS + "/" + request.getId() + "/positions/" + open.getId()))
                .andExpect(status().isOk());
        assertThat(plannedDate(open.getId())).isNull();
    }

    // --- upcoming ---

    @Test
    @DisplayName("upcoming - stage set, window, overdue flag, ordering, no money fields")
    void upcoming_filtersOrdersAndFlags() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity late = planned(project, PositionStatus.ORDERED, today().minusDays(5));
        PurchasePositionEntity soon = planned(project, PositionStatus.IN_DELIVERY, today().plusDays(3));
        planned(project, PositionStatus.WITH_BUYER, today().plusDays(40));
        planned(project, PositionStatus.RECEIVED, today().plusDays(2));
        planned(project, PositionStatus.CANCELLED, today().plusDays(2));
        planned(project, PositionStatus.ACCEPTED, today().plusDays(2));
        planned(project, PositionStatus.ORDERED, null);
        clear();

        mockMvc.perform(get(UPCOMING).param("days", "14").param("projectId", String.valueOf(project.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].positionId").value(late.getId()))
                .andExpect(jsonPath("$[0].overdue").value(true))
                .andExpect(jsonPath("$[0].status").value("ORDERED"))
                .andExpect(jsonPath("$[1].positionId").value(soon.getId()))
                .andExpect(jsonPath("$[1].overdue").value(false))
                .andExpect(jsonPath("$[1].projectId").value(project.getId()))
                .andExpect(jsonPath("$[0].salePrice").doesNotExist())
                .andExpect(jsonPath("$[0].plannedPurchasePrice").doesNotExist())
                .andExpect(jsonPath("$[0].deliveryCostShare").doesNotExist());

        mockMvc.perform(get(UPCOMING).param("days", "60").param("projectId", String.valueOf(project.getId())))
                .andExpect(jsonPath("$", hasSize(3)));
        // default window is 14 days
        mockMvc.perform(get(UPCOMING).param("projectId", String.valueOf(project.getId())))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("upcoming - days outside 1..60 -> 400 with the localized message")
    void upcoming_daysValidation() throws Exception {
        for (String days : new String[] {"0", "-1", "61"}) {
            mockMvc.perform(get(UPCOMING).header("Accept-Language", "pl").param("days", days))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Zakres dni musi mieścić się między 1 a 60."));
        }
    }

    @Test
    @DisplayName("upcoming - FOREMAN sees only member projects, BUYER sees all")
    void upcoming_scopePerRole() throws Exception {
        ProjectEntity mine = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        UserEntity foreman = memberUser("FOREMAN", mine);
        UserEntity buyer = user("BUYER");
        PurchasePositionEntity inMine = planned(mine, PositionStatus.ORDERED, today().plusDays(4));
        PurchasePositionEntity inOther = planned(other, PositionStatus.ORDERED, today().plusDays(4));
        clear();

        mockMvc.perform(get(UPCOMING).with(as(foreman, "FOREMAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.positionId == " + inMine.getId() + ")]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.positionId == " + inOther.getId() + ")]", hasSize(0)));
        mockMvc.perform(get(UPCOMING).with(as(foreman, "FOREMAN")).param("projectId", String.valueOf(other.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(UPCOMING).with(as(buyer, "BUYER")))
                .andExpect(jsonPath("$[?(@.positionId == " + inMine.getId() + ")]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.positionId == " + inOther.getId() + ")]", hasSize(1)));
    }
}
