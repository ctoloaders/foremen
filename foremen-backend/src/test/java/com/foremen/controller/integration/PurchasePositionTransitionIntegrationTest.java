package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-11-00 task 11.3 - {@code POST /api/purchase-positions/{id}/transition}: only MANUAL edges, the
 * proforma-paid gate, and the MANAGER / ADMIN restriction.
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 8.*, 9.8, 18.1.
 */
class PurchasePositionTransitionIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String PL_NOT_ALLOWED = "Ta zmiana statusu jest niedozwolona.";
    private static final String PL_GATE = "Zakup jest możliwy dopiero po pełnej płatności proformy przez klienta.";

    private ResultActions transition(Long id, PositionStatus target, RequestPostProcessor auth) throws Exception {
        var req = post(POSITIONS + "/" + id + "/transition").header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\": \"" + target + "\"}");
        return mockMvc.perform(auth == null ? req : req.with(auth));
    }

    private PositionStatus stored(PurchasePositionEntity p) {
        em.flush();
        em.clear();
        return em.find(PurchasePositionEntity.class, p.getId()).getStatus();
    }

    @Test
    @DisplayName("cancel from a pre-receipt stage, then reopen to PLANNED")
    void cancelAndReopen() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.IN_PROFORMA);

        transition(pos.getId(), PositionStatus.CANCELLED, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stored(pos)).isEqualTo(PositionStatus.CANCELLED);

        transition(pos.getId(), PositionStatus.PLANNED, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));
        assertThat(stored(pos)).isEqualTo(PositionStatus.PLANNED);
    }

    @Test
    @DisplayName("PACKAGE shortcut PLANNED -> WITH_BUYER and CLIENT_OWN shortcut PLANNED -> IN_DELIVERY")
    void shortcuts() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pkg = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        PurchasePositionEntity own = position(project, SourcingPath.CLIENT_OWN, PositionStatus.PLANNED);

        transition(pkg.getId(), PositionStatus.WITH_BUYER, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITH_BUYER"));
        transition(own.getId(), PositionStatus.IN_DELIVERY, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_DELIVERY"));
    }

    @Test
    @DisplayName("AGREED gate: entering WITH_BUYER from any stage but CLIENT_PAID -> 409 gateViolation")
    void agreedGate() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        for (PositionStatus from : new PositionStatus[] {
                PositionStatus.PLANNED, PositionStatus.ACCEPTED, PositionStatus.IN_PROFORMA}) {
            PurchasePositionEntity pos = position(project, SourcingPath.AGREED, from);
            transition(pos.getId(), PositionStatus.WITH_BUYER, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(PL_GATE));
            assertThat(stored(pos)).isEqualTo(from);
        }
    }

    @Test
    @DisplayName("system-only edges and same-stage requests -> 409 transition.notAllowed")
    void systemOnlyEdges_notAllowedManually() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity planned = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity received = position(project, SourcingPath.AGREED, PositionStatus.RECEIVED);
        PurchasePositionEntity inProforma = position(project, SourcingPath.AGREED, PositionStatus.IN_PROFORMA);

        transition(planned.getId(), PositionStatus.PROPOSED, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(PL_NOT_ALLOWED));
        transition(planned.getId(), PositionStatus.PLANNED, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(PL_NOT_ALLOWED));
        transition(received.getId(), PositionStatus.CANCELLED, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(PL_NOT_ALLOWED));
        transition(inProforma.getId(), PositionStatus.CLIENT_PAID, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(PL_NOT_ALLOWED));
        assertThat(stored(planned)).isEqualTo(PositionStatus.PLANNED);
    }

    @Test
    @DisplayName("a member MANAGER may transition; a BUYER with UPDATE and a FOREMAN may not (403)")
    void onlyManagerOrAdmin() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity buyer = user("BUYER");
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity manager = memberUser("MANAGER", project);

        transition(pos.getId(), PositionStatus.CANCELLED, as(buyer, "BUYER")).andExpect(status().isForbidden());
        transition(pos.getId(), PositionStatus.CANCELLED, as(foreman, "FOREMAN")).andExpect(status().isForbidden());
        assertThat(stored(pos)).isEqualTo(PositionStatus.PLANNED);

        transition(pos.getId(), PositionStatus.CANCELLED, as(manager, "MANAGER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("a MANAGER outside the project gets 404 and nothing changes")
    void outOfScope_returns404() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity outsider = user("MANAGER");

        transition(pos.getId(), PositionStatus.CANCELLED, as(outsider, "MANAGER")).andExpect(status().isNotFound());
        assertThat(stored(pos)).isEqualTo(PositionStatus.PLANNED);
    }

    @Test
    @DisplayName("missing target -> 400")
    void invalidTarget_returns400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity pos = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        mockMvc.perform(post(POSITIONS + "/" + pos.getId() + "/transition")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
