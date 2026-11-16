package com.foremen.controller.integration;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.payment.ScheduleSource;
import com.foremen.service.payment.ScheduleStatus;
import com.foremen.service.payment.TrancheStatus;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-06 task 2.8 (R1.3 - R1.9, R3.1, R3.4): the unified list, filters, summary and the per-role kind rule
 * through {@code /api/client-payments}, against a real PostgreSQL and the real specification.
 *
 * <p>Fixture (project A): works tranches PLANNED (+10 d, 1000.00), PAID (-30 d, 500.00 / 500.00), stored
 * OVERDUE (-5 d, 300.00 / 100.00); materials DRAFT (no due date, 200.00), ISSUED past due (-2 d, 400.00),
 * PAID (-20 d, 100.00 / 100.00). Project B holds one works tranche nobody is a member of.
 *
 * <p>Repeatability: unique per-run data inside the rolled-back transaction of the base class.
 *
 * <p>Validates: Requirements 1.3, 1.4, 1.5, 1.7, 1.9, 2.2, 3.1, 3.4
 */
class ClientPaymentListIntegrationTest extends ProcurementIntegrationTestBase {

    private static final String URL = "/api/client-payments";

    private ProjectEntity projectA;
    private ProjectEntity projectB;
    private PaymentTrancheEntity planned;
    private PaymentTrancheEntity paidTranche;
    private PaymentTrancheEntity overdueTranche;
    private ClientProformaEntity draft;
    private ClientProformaEntity issuedLate;
    private ClientProformaEntity paidProforma;
    private PaymentTrancheEntity otherProject;
    private RequestPostProcessor adminCaller;

    @BeforeEach
    void seedPayments() {
        ResourceEntity cp = resource("CLIENT_PAYMENTS");
        OperationEntity create = operation("CREATE");
        OperationEntity read = operation("READ");
        OperationEntity update = operation("UPDATE");
        OperationEntity delete = operation("DELETE");
        grant("MANAGER", cp, create, read, update, delete);
        grant("FINANCIER", cp, create, read, update, delete);
        grant("FOREMAN", cp, read);
        grant("BUYER", cp, read);
        grant("CLIENT", cp, read);
        role("FINANCIER");
        em.flush();
        for (String code : java.util.List.of("MANAGER", "FINANCIER", "FOREMAN", "BUYER", "CLIENT")) {
            permissionCache.invalidate(code);
        }

        projectA = project(ProjectStatus.ACTIVE);
        projectB = project(ProjectStatus.ACTIVE);
        LocalDate today = LocalDate.now();

        PaymentScheduleEntity scheduleA = schedule(projectA, ScheduleStatus.PUBLISHED);
        planned = tranche(scheduleA, 1, TrancheStatus.PENDING, today.plusDays(10), "1000.00", "0.00");
        paidTranche = tranche(scheduleA, 2, TrancheStatus.PAID, today.minusDays(30), "500.00", "500.00");
        overdueTranche = tranche(scheduleA, 3, TrancheStatus.OVERDUE, today.minusDays(5), "300.00", "100.00");
        draft = proforma(projectA, ProformaStatus.DRAFT, null, null, "200.00", "0.00", "buyer draft");
        issuedLate = proforma(projectA, ProformaStatus.ISSUED, "PF/IT/0001", today.minusDays(2), "400.00",
                "0.00", "INTERNAL_NOTE_SENTINEL");
        paidProforma = proforma(projectA, ProformaStatus.PAID, "PF/IT/0002", today.minusDays(20), "100.00",
                "100.00", null);
        otherProject = tranche(schedule(projectB, ScheduleStatus.PUBLISHED), 1, TrancheStatus.PENDING,
                today.plusDays(3), "777.00", "0.00");
        adminCaller = as(user("ADMIN"), "ADMIN");
        em.flush();
        em.clear();
    }

    // ------------------------------------------------------------------ staff: list, order, filters

    @Test
    @DisplayName("ADMIN lists both kinds of a project in the order dueDate ASC NULLS LAST, id ASC")
    void adminListsBothKindsInDefaultOrder() throws Exception {
        list(null, "projectId=" + projectA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[*].id").value(contains(
                        id(paidTranche), id(paidProforma), id(overdueTranche), id(issuedLate), id(planned),
                        id(draft))));
    }

    @Test
    @DisplayName("kind filter keeps one kind; both kinds carry the mapped display status")
    void kindFilterAndDisplayStatus() throws Exception {
        list(null, "projectId=" + projectA.getId() + "&kind=WORKS")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].kind").value(everyItem(is("WORKS"))))
                .andExpect(jsonPath("$.content[?(@.id==" + id(planned) + ")].status").value(contains("PLANNED")));
        list(null, "projectId=" + projectA.getId() + "&kind=MATERIALS")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].kind").value(everyItem(is("MATERIALS"))))
                .andExpect(jsonPath("$.content[?(@.id==" + id(issuedLate) + ")].status")
                        .value(contains("OVERDUE")))
                .andExpect(jsonPath("$.content[?(@.id==" + id(issuedLate) + ")].storedStatus")
                        .value(contains("ISSUED")));
    }

    @Test
    @DisplayName("status OVERDUE matches the stored works OVERDUE and a past-due issued proforma")
    void overdueStatusFilterSpansBothKinds() throws Exception {
        list(null, "projectId=" + projectA.getId() + "&status=OVERDUE")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(
                        id(overdueTranche), id(issuedLate))));
        list(null, "projectId=" + projectA.getId() + "&status=PAID")
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(id(paidTranche), id(paidProforma))));
        list(null, "projectId=" + projectA.getId() + "&status=PLANNED")
                .andExpect(jsonPath("$.content[*].id").value(contains(id(planned))));
        list(null, "projectId=" + projectA.getId() + "&status=DRAFT")
                .andExpect(jsonPath("$.content[*].id").value(contains(id(draft))));
    }

    @Test
    @DisplayName("overdue flag true / false partitions the project's payments")
    void overdueFlagPartitions() throws Exception {
        list(null, "projectId=" + projectA.getId() + "&overdue=true")
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(
                        id(overdueTranche), id(issuedLate))));
        list(null, "projectId=" + projectA.getId() + "&overdue=false")
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(overdueTranche)))));
    }

    @Test
    @DisplayName("due range is inclusive and excludes a draft without a due date")
    void dueRangeFilter() throws Exception {
        LocalDate today = LocalDate.now();
        list(null, "projectId=" + projectA.getId() + "&dueFrom=" + today.minusDays(5) + "&dueTo="
                + today.minusDays(2))
                .andExpect(jsonPath("$.content[*].id").value(containsInAnyOrder(
                        id(overdueTranche), id(issuedLate))));
        list(null, "projectId=" + projectA.getId() + "&dueTo=" + today.plusDays(30))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(draft)))));
    }

    @Test
    @DisplayName("text query matches the proforma number or the note, case-insensitively; '_' is not a wildcard")
    void textQuery() throws Exception {
        list(null, "projectId=" + projectA.getId() + "&query=pf/it/0001")
                .andExpect(jsonPath("$.content[*].id").value(contains(id(issuedLate))));
        list(null, "projectId=" + projectA.getId() + "&query=BUYER")
                .andExpect(jsonPath("$.content[*].id").value(contains(id(draft))));
        list(null, "projectId=" + projectA.getId() + "&query=pf_it")
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("summary aggregates the same filtered set, not the page")
    void summaryMatchesFilter() throws Exception {
        summary(null, "projectId=" + projectA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(6))
                .andExpect(jsonPath("$.amountGross").value(2500.00))
                .andExpect(jsonPath("$.paidGross").value(700.00))
                .andExpect(jsonPath("$.remainingGross").value(1800.00));
        summary(null, "projectId=" + projectA.getId() + "&kind=WORKS")
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.amountGross").value(1800.00))
                .andExpect(jsonPath("$.paidGross").value(600.00))
                .andExpect(jsonPath("$.remainingGross").value(1200.00));
        summary(null, "projectId=" + projectA.getId() + "&status=OVERDUE")
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.amountGross").value(700.00))
                .andExpect(jsonPath("$.remainingGross").value(600.00));
    }

    // ------------------------------------------------------------------ role kind rule and scope

    @Test
    @DisplayName("FOREMAN member sees only WORKS rows of own projects; summary agrees; MATERIALS by id is 404")
    void foremanSeesWorksOnly() throws Exception {
        UserEntity foreman = memberUser("FOREMAN", projectA);
        RequestPostProcessor caller = as(foreman, "FOREMAN");
        list(caller, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].kind").value(everyItem(is("WORKS"))))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(otherProject)))));
        summary(caller, null).andExpect(jsonPath("$.count").value(3));
        one(caller, id(issuedLate)).andExpect(status().isNotFound());
        one(caller, id(planned)).andExpect(status().isOk());
        one(caller, id(otherProject)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("BUYER sees only MATERIALS rows (company-wide) and no WORKS row")
    void buyerSeesMaterialsOnly() throws Exception {
        UserEntity buyer = user("BUYER");
        RequestPostProcessor caller = as(buyer, "BUYER");
        list(caller, "projectId=" + projectA.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].kind").value(everyItem(is("MATERIALS"))));
        list(caller, "projectId=" + projectB.getId()).andExpect(jsonPath("$.totalElements").value(0));
        summary(caller, "projectId=" + projectA.getId())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.amountGross").value(700.00));
        one(caller, id(planned)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FINANCIER (company-wide) lists both kinds but cannot create a materials proforma")
    void financierSeesBothKindsReadsMaterials() throws Exception {
        UserEntity financier = user("FINANCIER");
        RequestPostProcessor caller = as(financier, "FINANCIER");
        list(caller, "projectId=" + projectA.getId())
                .andExpect(jsonPath("$.totalElements").value(6));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(URL + "/materials").with(caller)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": " + projectA.getId() + ", \"positionIds\": [1]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CLIENT member sees published works and non-draft materials as the client-safe DTO")
    void clientSeesClientSafeRows() throws Exception {
        UserEntity client = memberUser("CLIENT", projectA);
        RequestPostProcessor caller = as(client, "CLIENT");
        list(caller, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(draft)))))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id(otherProject)))))
                .andExpect(jsonPath("$.content[0].note").doesNotExist())
                .andExpect(jsonPath("$.content[0].storedStatus").doesNotExist())
                .andExpect(jsonPath("$.content[0].projectId").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("INTERNAL_NOTE_SENTINEL"))));
        one(caller, id(draft)).andExpect(status().isNotFound());
        summary(caller, null).andExpect(jsonPath("$.count").value(5));
    }

    @Test
    @DisplayName("CLIENT does not see the works rows of a DRAFT schedule")
    void clientDoesNotSeeDraftSchedule() throws Exception {
        PaymentScheduleEntity s = em.find(PaymentScheduleEntity.class,
                em.find(PaymentTrancheEntity.class, planned.getId()).getSchedule().getId());
        s.setStatus(ScheduleStatus.DRAFT);
        em.flush();
        em.clear();
        UserEntity client = memberUser("CLIENT", projectA);
        list(as(client, "CLIENT"), null)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].kind").value(everyItem(is("MATERIALS"))));
    }

    @Test
    @DisplayName("a user without membership sees an empty list and a 404 by id")
    void outsiderSeesNothing() throws Exception {
        UserEntity outsider = user("FOREMAN");
        RequestPostProcessor caller = as(outsider, "FOREMAN");
        list(caller, null).andExpect(jsonPath("$.totalElements").value(0));
        summary(caller, null).andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.amountGross").value(0));
        one(caller, id(planned)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a role without a CLIENT_PAYMENTS grant is refused with 403")
    void roleWithoutGrantIsForbidden() throws Exception {
        UserEntity worker = memberUser("WORKER", projectA);
        list(as(worker, "WORKER"), null).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions list(RequestPostProcessor caller, String query) throws Exception {
        return call(URL, caller, query);
    }

    private ResultActions summary(RequestPostProcessor caller, String query) throws Exception {
        return call(URL + "/summary", caller, query);
    }

    private ResultActions one(RequestPostProcessor caller, long id) throws Exception {
        return call(URL + "/" + id, caller, null);
    }

    private ResultActions call(String path, RequestPostProcessor caller, String query) throws Exception {
        var req = get(path + (query == null ? "" : "?" + query)).header("Accept-Language", "pl");
        // null = ADMIN; the service resolves the role from the numeric principal, so a real ADMIN user is needed
        return mockMvc.perform(req.with(caller == null ? adminCaller : caller));
    }

    private static int id(PaymentTrancheEntity t) {
        return t.getPayment().getId().intValue();
    }

    private static int id(ClientProformaEntity p) {
        return p.getPayment().getId().intValue();
    }

    private PaymentScheduleEntity schedule(ProjectEntity project, ScheduleStatus scheduleStatus) {
        PaymentScheduleEntity s = new PaymentScheduleEntity();
        s.setProject(project);
        s.setCurrency("PLN");
        s.setBasisGross(new BigDecimal("10000.00"));
        s.setSource(ScheduleSource.MANUAL);
        s.setStatus(scheduleStatus);
        em.persist(s);
        return s;
    }

    private PaymentTrancheEntity tranche(PaymentScheduleEntity schedule, int seq, TrancheStatus status,
                                         LocalDate due, String amount, String paid) {
        PaymentTrancheEntity t = new PaymentTrancheEntity();
        t.setSchedule(schedule);
        t.setSeqNo(seq);
        t.setDueDate(due);
        t.setAmountGross(new BigDecimal(amount));
        t.setPaidGross(new BigDecimal(paid));
        t.setStatus(status);
        em.persist(t);
        em.flush();
        return t;
    }

    private ClientProformaEntity proforma(ProjectEntity project, ProformaStatus status, String number,
                                          LocalDate due, String total, String paid, String note) {
        ClientProformaEntity p = new ClientProformaEntity();
        p.setProject(project);
        p.setStatus(status);
        p.setNumber(number);
        p.setDueDate(due);
        p.setTotalGross(new BigDecimal(total));
        p.setPaidGross(new BigDecimal(paid));
        p.setNote(note);
        em.persist(p);
        em.flush();
        return p;
    }
}
