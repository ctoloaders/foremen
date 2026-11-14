package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
 * FOR-11-00 task 11.3 - {@code /api/purchase-requests}: CRUD, buyer validation, attach / detach,
 * derived {@code status} / {@code overdue} / {@code positionCount}, delete of a non-empty request, scoping.
 *
 * <p>Setup is flushed and the persistence context cleared before each HTTP call so that lazily loaded
 * {@code positions} collections reflect the database (production uses one session per request).
 *
 * <p>Repeatability: unique per-run data, rolled-back transaction.
 *
 * <p>Validates Requirements 11.*, 9.2, 13.4, 18.1.
 */
class PurchaseRequestIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String PL_BUYER_INVALID = "Przypisany użytkownik musi mieć rolę zakupowca.";
    private static final String PL_MISMATCH = "Pozycja należy do innego projektu.";
    private static final String PL_ASSIGNED = "Pozycja jest już w innym zleceniu.";
    private static final String PL_DETACH = "Pozycji nie można usunąć ze zlecenia na tym etapie.";
    private static final String PL_NOT_EMPTY = "Zlecenie zawiera pozycje.";

    private void clear() {
        em.flush();
        em.clear();
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post(REQUESTS).header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(Long id, String body) throws Exception {
        return mockMvc.perform(put(REQUESTS + "/" + id).header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions attach(Long requestId, Long... positionIds) throws Exception {
        String ids = String.join(",", java.util.Arrays.stream(positionIds).map(String::valueOf).toList());
        return mockMvc.perform(post(REQUESTS + "/" + requestId + "/positions").header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content("{\"positionIds\": [" + ids + "]}"));
    }

    private ResultActions detach(Long requestId, Long positionId) throws Exception {
        return mockMvc.perform(delete(REQUESTS + "/" + requestId + "/positions/" + positionId)
                .header("Accept-Language", "pl"));
    }

    // --- create / update / buyer validation ---

    @Test
    @DisplayName("POST - creates an OPEN request with positionCount 0 and the assigned buyer's name")
    void create_succeeds() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        clear();

        create("{\"projectId\": " + project.getId() + ", \"referenceNumber\": \"R-1\", \"assignedBuyerId\": "
                + buyer.getId() + ", \"expectedDeliveryDate\": \"" + today().plusDays(10) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.projectId").value(project.getId()))
                .andExpect(jsonPath("$.assignedBuyerId").value(buyer.getId()))
                .andExpect(jsonPath("$.assignedBuyerName").value(buyer.getName()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.positionCount").value(0));
    }

    @Test
    @DisplayName("POST - unknown project -> 404")
    void create_unknownProject_returns404() throws Exception {
        create("{\"projectId\": 999999999}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST / PUT - a buyer that is not an active BUYER -> 400 buyer.invalid")
    void buyerValidation() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity foreman = user("FOREMAN");
        UserEntity inactiveBuyer = user("BUYER");
        inactiveBuyer.setActive(false);
        PurchaseRequestEntity existing = request(project);
        clear();

        for (Long invalid : new Long[] {foreman.getId(), inactiveBuyer.getId(), 999999999L}) {
            create("{\"projectId\": " + project.getId() + ", \"assignedBuyerId\": " + invalid + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(PL_BUYER_INVALID));
            update(existing.getId(), "{\"assignedBuyerId\": " + invalid + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(PL_BUYER_INVALID));
        }
    }

    @Test
    @DisplayName("PUT - updates fields and may assign a valid buyer")
    void update_succeeds() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        PurchaseRequestEntity r = request(project);
        clear();

        update(r.getId(), "{\"notes\": \"hello\", \"assignedBuyerId\": " + buyer.getId() + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("hello"))
                .andExpect(jsonPath("$.assignedBuyerId").value(buyer.getId()));
    }

    @Test
    @DisplayName("only MANAGER / ADMIN hold CREATE: BUYER and FOREMAN -> 403")
    void create_grantMatrix() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity buyer = user("BUYER");
        UserEntity foreman = memberUser("FOREMAN", project);
        UserEntity manager = memberUser("MANAGER", project);
        clear();
        String body = "{\"projectId\": " + project.getId() + "}";

        mockMvc.perform(post(REQUESTS).with(as(buyer, "BUYER")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(REQUESTS).with(as(foreman, "FOREMAN")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(REQUESTS).with(as(manager, "MANAGER")).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // --- derived fields ---

    @Test
    @DisplayName("derived status: OPEN -> RELEASED (all >= WITH_BUYER) -> COMPLETED (all RECEIVED) / CANCELLED")
    void derivedStatus() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity open = request(project);
        PurchaseRequestEntity released = request(project);
        PurchaseRequestEntity completed = request(project);
        PurchaseRequestEntity cancelled = request(project);
        attach(position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER), open);
        attach(position(project, SourcingPath.AGREED, PositionStatus.PLANNED), open);
        attach(position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER), released);
        attach(position(project, SourcingPath.AGREED, PositionStatus.IN_DELIVERY), released);
        attach(position(project, SourcingPath.AGREED, PositionStatus.RECEIVED), completed);
        attach(position(project, SourcingPath.AGREED, PositionStatus.CANCELLED), completed);
        attach(position(project, SourcingPath.AGREED, PositionStatus.CANCELLED), cancelled);
        clear();

        expectDerived(open, "OPEN", 2);
        expectDerived(released, "RELEASED", 2);
        expectDerived(completed, "COMPLETED", 2);
        expectDerived(cancelled, "CANCELLED", 1);
    }

    private void expectDerived(PurchaseRequestEntity r, String status, int count) throws Exception {
        mockMvc.perform(get(REQUESTS + "/" + r.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.positionCount").value(count));
    }

    @Test
    @DisplayName("overdue: past expected date and not COMPLETED / CANCELLED")
    void overdue() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        LocalDate past = today().minusDays(30);
        PurchaseRequestEntity late = request(project);
        late.setExpectedDeliveryDate(past);
        PurchaseRequestEntity future = request(project);
        future.setExpectedDeliveryDate(today().plusDays(30));
        PurchaseRequestEntity done = request(project);
        done.setExpectedDeliveryDate(past);
        attach(position(project, SourcingPath.AGREED, PositionStatus.RECEIVED), done);
        PurchaseRequestEntity noDate = request(project);
        clear();

        mockMvc.perform(get(REQUESTS + "/" + late.getId())).andExpect(jsonPath("$.overdue").value(true));
        mockMvc.perform(get(REQUESTS + "/" + future.getId())).andExpect(jsonPath("$.overdue").value(false));
        mockMvc.perform(get(REQUESTS + "/" + done.getId())).andExpect(jsonPath("$.overdue").value(false));
        mockMvc.perform(get(REQUESTS + "/" + noDate.getId())).andExpect(jsonPath("$.overdue").value(false));
    }

    // --- attach / detach ---

    @Test
    @DisplayName("attach - same-project positions are added; re-attaching to the same request is a no-op")
    void attach_succeeds() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        PurchasePositionEntity p1 = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity p2 = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        clear();

        attach(r.getId(), p1.getId(), p2.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionCount").value(2));
        clear();
        attach(r.getId(), p1.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.positionCount").value(2));
        clear();
        assertThat(em.find(PurchasePositionEntity.class, p1.getId()).getPurchaseRequest().getId())
                .isEqualTo(r.getId());
    }

    @Test
    @DisplayName("attach - a position of another project -> 400 projectMismatch")
    void attach_otherProject_returns400() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        PurchasePositionEntity foreign = position(other, SourcingPath.AGREED, PositionStatus.PLANNED);
        clear();

        attach(r.getId(), foreign.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PL_MISMATCH));
    }

    @Test
    @DisplayName("attach - a position already in another request -> 409 alreadyAssigned")
    void attach_alreadyAssigned_returns409() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity first = request(project);
        PurchaseRequestEntity second = request(project);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        attach(p, first);
        clear();

        attach(second.getId(), p.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_ASSIGNED));
    }

    @Test
    @DisplayName("attach - a CANCELLED position -> 409; unknown position -> 404")
    void attach_cancelledOrUnknown() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        PurchasePositionEntity cancelled = position(project, SourcingPath.AGREED, PositionStatus.CANCELLED);
        clear();

        attach(r.getId(), cancelled.getId()).andExpect(status().isConflict());
        attach(r.getId(), 999999999L).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("detach - allowed at PLANNED / PROPOSED / ACCEPTED, 409 detachNotAllowed from IN_PROFORMA on")
    void detach_stageRule() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        PurchasePositionEntity accepted = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity withBuyer = position(project, SourcingPath.AGREED, PositionStatus.WITH_BUYER);
        PurchasePositionEntity inProforma = position(project, SourcingPath.AGREED, PositionStatus.IN_PROFORMA);
        attach(accepted, r);
        attach(withBuyer, r);
        attach(inProforma, r);
        clear();

        detach(r.getId(), withBuyer.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_DETACH));
        detach(r.getId(), inProforma.getId()).andExpect(status().isConflict());
        detach(r.getId(), accepted.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.positionCount").value(2));
        clear();
        assertThat(em.find(PurchasePositionEntity.class, accepted.getId()).getPurchaseRequest()).isNull();
    }

    @Test
    @DisplayName("detach - a position that is not in the request -> 404")
    void detach_notInRequest_returns404() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        PurchasePositionEntity loose = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        clear();

        detach(r.getId(), loose.getId()).andExpect(status().isNotFound());
    }

    // --- delete ---

    @Test
    @DisplayName("DELETE - a request with positions -> 409 delete.notEmpty; an empty one is removed")
    void delete_rules() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity full = request(project);
        attach(position(project, SourcingPath.AGREED, PositionStatus.PLANNED), full);
        PurchaseRequestEntity empty = request(project);
        clear();

        mockMvc.perform(delete(REQUESTS + "/" + full.getId()).header("Accept-Language", "pl"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_NOT_EMPTY));
        assertThat(em.find(PurchaseRequestEntity.class, full.getId())).isNotNull();

        mockMvc.perform(delete(REQUESTS + "/" + empty.getId())).andExpect(status().is2xxSuccessful());
        clear();
        assertThat(em.find(PurchaseRequestEntity.class, empty.getId())).isNull();
    }

    // --- scoping ---

    @Test
    @DisplayName("scoping - member sees own project's requests only; BUYER sees all; non-member none")
    void scoping() throws Exception {
        ProjectEntity a = project(ProjectStatus.ACTIVE);
        ProjectEntity b = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity inA = request(a);
        PurchaseRequestEntity inB = request(b);
        UserEntity foreman = memberUser("FOREMAN", a);
        UserEntity outsider = user("FOREMAN");
        UserEntity buyer = user("BUYER");
        clear();
        int idA = Math.toIntExact(inA.getId());
        int idB = Math.toIntExact(inB.getId());

        mockMvc.perform(get(REQUESTS).param("size", "200").with(as(foreman, "FOREMAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(idA)))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(idB))));
        mockMvc.perform(get(REQUESTS + "/" + inB.getId()).with(as(foreman, "FOREMAN")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(REQUESTS).param("size", "200").with(as(outsider, "FOREMAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(idA))))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(idB))));

        mockMvc.perform(get(REQUESTS).param("size", "200").with(as(buyer, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(idA)))
                .andExpect(jsonPath("$.content[*].id").value(hasItem(idB)));
    }

    @Test
    @DisplayName("a BUYER (UPDATE grant) may attach positions of any project's request; a FOREMAN may not (403)")
    void attach_grants() throws Exception {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchaseRequestEntity r = request(project);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        UserEntity buyer = user("BUYER");
        UserEntity foreman = memberUser("FOREMAN", project);
        clear();
        String body = "{\"positionIds\": [" + p.getId() + "]}";

        mockMvc.perform(post(REQUESTS + "/" + r.getId() + "/positions").with(as(foreman, "FOREMAN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(REQUESTS + "/" + r.getId() + "/positions").with(as(buyer, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionCount").value(1));
    }
}
