package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * FOR-07-03 task 5.3 - {@code /api/expenses} over the real HTTP + Spring Security +
 * {@code PermissionInterceptor} stack with the matrix as changeset 210 seeds it
 * (ADMIN, FINANCIER, MANAGER, FOREMAN, WORKER read + create; CLIENT and ESTIMATOR none).
 *
 * <p>Repeatability: generator strategy - users and projects carry a per-run id; every upload creates a
 * new document whose id is asserted individually. Object storage is a mock, OCR is disabled.
 *
 * <p>Validates: Requirements 7.3, 7.4, 10.2, 10.3, 10.4
 */
@Tag("Feature: FOR-07-03-company-procurement, task 5.3: expense controller")
class ExpenseControllerIT extends CompanyProcurementItBase {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("R10.2: seeded EXPENSE_UPLOAD grants - five internal roles READ+CREATE, CLIENT and ESTIMATOR none")
    void seededMatrix() throws Exception {
        for (String role : List.of("ADMIN", "FINANCIER", "MANAGER", "FOREMAN", "WORKER")) {
            assertThat(BankLedgerItSupport.operationsFor(POSTGRES, role, "EXPENSE_UPLOAD"))
                    .as(role).containsExactly("CREATE", "READ");
        }
        for (String role : List.of("CLIENT", "ESTIMATOR")) {
            assertThat(BankLedgerItSupport.operationsFor(POSTGRES, role, "EXPENSE_UPLOAD")).as(role).isEmpty();
        }
    }

    @Test
    @DisplayName("unauthenticated upload is 401")
    void unauthenticated() throws Exception {
        assertThat(mockMvc.perform(upload()).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("R7.3: WORKER uploads without a project (no-project document) and with a project they are a member of")
    void workerUploads() throws Exception {
        long project = createActiveProject("worker");
        addMember("WORKER", project);
        String worker = bearer("WORKER");

        JsonNode company = send(upload().header("Authorization", worker), 200);
        assertThat(company.get("projectId").isNull()).isTrue();
        assertThat(countDocuments(company.get("id").asLong(), null)).isEqualTo(1);

        JsonNode inProject = send(upload().param("projectId", String.valueOf(project)).header("Authorization", worker), 200);
        assertThat(inProject.get("projectId").asLong()).isEqualTo(project);
        assertThat(countDocuments(inProject.get("id").asLong(), project)).isEqualTo(1);
    }

    @Test
    @DisplayName("R7.3: a non-member project is 404 and nothing is created; ADMIN needs no membership")
    void nonMemberIs404() throws Exception {
        long project = createActiveProject("nonmember");
        long before = documentCount();

        MvcResult r = mockMvc.perform(upload().param("projectId", String.valueOf(project))
                .header("Authorization", bearer("WORKER"))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(documentCount()).isEqualTo(before);

        JsonNode admin = send(upload().param("projectId", String.valueOf(project)).header("Authorization", bearer("ADMIN")), 200);
        assertThat(admin.get("projectId").asLong()).isEqualTo(project);
    }

    @Test
    @DisplayName("R7.4: PAID_CASH requires a positive amount (400, nothing created)")
    void cashValidation() throws Exception {
        long before = documentCount();
        String worker = bearer("WORKER");
        assertThat(mockMvc.perform(upload().param("paymentChoice", "PAID_CASH")
                .header("Authorization", worker)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(upload().param("paymentChoice", "PAID_CASH").param("cashAmount", "0")
                .header("Authorization", worker)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mockMvc.perform(upload().param("paymentChoice", "PAID_CASH").param("cashAmount", "-5")
                .header("Authorization", worker)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(documentCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("R7.2: LINKED_FROM_LEDGER and FIND_BANK_TRANSACTION are refused with 400")
    void forbiddenPaymentChoices() throws Exception {
        long before = documentCount();
        for (String choice : List.of("LINKED_FROM_LEDGER", "FIND_BANK_TRANSACTION")) {
            MvcResult r = mockMvc.perform(upload().param("paymentChoice", choice)
                    .header("Authorization", bearer("FINANCIER"))).andReturn();
            assertThat(r.getResponse().getStatus()).as(choice).isEqualTo(400);
        }
        assertThat(documentCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("R10.4: CLIENT (and ESTIMATOR) are 403 on every handler; WORKER options hide cash accounts")
    void clientForbiddenAndOptions() throws Exception {
        for (String role : List.of("CLIENT", "ESTIMATOR")) {
            String auth = bearer(role);
            List<String> failures = new ArrayList<>();
            if (mockMvc.perform(upload().header("Authorization", auth)).andReturn()
                    .getResponse().getStatus() != 403) {
                failures.add("POST");
            }
            if (mockMvc.perform(get("/api/expenses/options").header("Authorization", auth)).andReturn()
                    .getResponse().getStatus() != 403) {
                failures.add("GET options");
            }
            if (mockMvc.perform(get("/api/expenses/1/status").header("Authorization", auth)).andReturn()
                    .getResponse().getStatus() != 403) {
                failures.add("GET status");
            }
            assertThat(failures).as(role + " must be denied").isEmpty();
        }
        long project = createActiveProject("options");
        addMember("WORKER", project);
        JsonNode options = send(get("/api/expenses/options").header("Authorization", bearer("WORKER")), 200);
        assertThat(options.get("canChooseCashAccount").asBoolean()).isFalse();
        assertThat(options.get("cashAccounts")).isEmpty();
        List<Long> projectIds = new ArrayList<>();
        options.get("projects").forEach(p -> projectIds.add(p.get("id").asLong()));
        assertThat(projectIds).containsExactly(project);
    }

    // --- helpers -------------------------------------------------------------------------------

    private MockMultipartHttpServletRequestBuilder upload() {
        return multipart("/api/expenses").file(new MockMultipartFile(
                "file", "receipt.png", "image/png", new byte[] {1, 2, 3, 4}));
    }

    private JsonNode send(org.springframework.test.web.servlet.RequestBuilder request, int expected)
            throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private long documentCount() throws Exception {
        return BankLedgerItSupport.queryForLong(POSTGRES, "SELECT COUNT(*) FROM procurement_documents");
    }

    private long countDocuments(long id, Long projectId) throws Exception {
        return projectId == null
                ? BankLedgerItSupport.queryForLong(POSTGRES,
                        "SELECT COUNT(*) FROM procurement_documents WHERE id = ? AND project_id IS NULL", id)
                : BankLedgerItSupport.queryForLong(POSTGRES,
                        "SELECT COUNT(*) FROM procurement_documents WHERE id = ? AND project_id = ?", id, projectId);
    }
}
