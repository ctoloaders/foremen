package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * FOR-07-03 task 5.3 - {@code /api/company-purchases} over the real HTTP + Spring Security +
 * {@code PermissionInterceptor} stack with the matrix exactly as changeset 209 seeds it
 * (ADMIN / FINANCIER read-update-delete, MANAGER read only, everyone else denied).
 *
 * <p>Repeatability: generator strategy - users, projects, suppliers carry a per-run id and every list
 * probe filters by that id ({@code q}), so rows from other runs never interfere.
 *
 * <p>Validates: Requirements 4.2, 4.3, 5.1, 10.1, 10.3, 10.4
 */
@Tag("Feature: FOR-07-03-company-procurement, task 5.3: company purchases controller")
class CompanyPurchasesControllerIT extends CompanyProcurementItBase {

    private static final long MISSING = 987_654_321L;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("R10.1: seeded COMPANY_PURCHASES grants - ADMIN/FINANCIER R/U/D, MANAGER R, others none")
    void seededMatrix() throws Exception {
        assertThat(BankLedgerItSupport.operationsFor(POSTGRES, "ADMIN", "COMPANY_PURCHASES"))
                .containsExactly("DELETE", "READ", "UPDATE");
        assertThat(BankLedgerItSupport.operationsFor(POSTGRES, "FINANCIER", "COMPANY_PURCHASES"))
                .containsExactly("DELETE", "READ", "UPDATE");
        assertThat(BankLedgerItSupport.operationsFor(POSTGRES, "MANAGER", "COMPANY_PURCHASES"))
                .containsExactly("READ");
        for (String role : List.of("FOREMAN", "WORKER", "CLIENT", "ESTIMATOR")) {
            assertThat(BankLedgerItSupport.operationsFor(POSTGRES, role, "COMPANY_PURCHASES"))
                    .as(role).isEmpty();
        }
    }

    @Test
    @DisplayName("unauthenticated call is 401")
    void unauthenticated() throws Exception {
        assertThat(status(get("/api/company-purchases/documents"))).isEqualTo(401);
    }

    @Test
    @DisplayName("R10.3 / 10.4: role matrix over every handler - allowed reaches the handler, denied is 403")
    void roleMatrix() throws Exception {
        Map<String, Set<String>> grants = Map.of(
                "ADMIN", Set.of("READ", "UPDATE", "DELETE"),
                "FINANCIER", Set.of("READ", "UPDATE", "DELETE"),
                "MANAGER", Set.of("READ"),
                "FOREMAN", Set.of(),
                "WORKER", Set.of(),
                "CLIENT", Set.of());
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Set<String>> role : grants.entrySet()) {
            String auth = bearer(role.getKey());
            for (Probe p : probes()) {
                int s = status(p.request().header("Authorization", auth));
                boolean allowed = role.getValue().contains(p.operation());
                boolean ok = allowed ? s == p.statusWhenAllowed() : s == 403;
                if (!ok) {
                    failures.add(role.getKey() + " " + p.name() + " -> " + s
                            + (allowed ? " (expected " + p.statusWhenAllowed() + ")" : " (expected 403)"));
                }
            }
        }
        assertThat(failures).as("ABAC matrix violations").isEmpty();
    }

    @Test
    @DisplayName("R4.2 / 4.3: filters - all, projectId, noProject, linkStatus, conflict is 400; MANAGER (non-member) sees all")
    void filters() throws Exception {
        long project = createActiveProject("filters");
        String tag = "supp" + runId;
        long withProject = createDocument(project, "FOREMAN", tag + "-p");
        long noProject = createDocument(null, "WORKER", tag + "-n");

        // MANAGER is not a member of the project yet reads every project and no-project documents.
        String manager = bearer("MANAGER");
        assertThat(ids(list(manager, "q=" + tag))).containsExactlyInAnyOrder(withProject, noProject);
        assertThat(ids(list(manager, "q=" + tag + "&projectId=" + project))).containsExactly(withProject);
        assertThat(ids(list(manager, "q=" + tag + "&noProject=true"))).containsExactly(noProject);
        assertThat(ids(list(manager, "q=" + tag + "&linkStatus=NO_PROJECT"))).containsExactly(noProject);
        assertThat(ids(list(manager, "q=" + tag + "&linkStatus=UNLINKED"))).containsExactly(withProject);

        MvcResult conflict = mockMvc.perform(get("/api/company-purchases/documents?projectId=" + project
                + "&noProject=true").header("Authorization", manager)).andReturn();
        assertThat(conflict.getResponse().getStatus()).isEqualTo(400);

        // flat lines honour the same filters
        JsonNode lines = body(get("/api/company-purchases/lines?q=" + tag + "&noProject=true"), manager, 200);
        assertThat(lines.get("content")).hasSize(1);
    }

    @Test
    @DisplayName("R4.2: FINANCIER reads a no-project original with signed image URLs")
    void financierSeesNoProjectOriginal() throws Exception {
        long doc = createDocument(null, "WORKER", "orig" + runId);
        JsonNode detail = body(get("/api/company-purchases/documents/" + doc), bearer("FINANCIER"), 200);
        JsonNode row = detail.get("document");
        assertThat(row.get("id").asLong()).isEqualTo(doc);
        assertThat(row.get("projectId").isNull()).isTrue();
        assertThat(row.get("rawImageUrl").asText()).isEqualTo("https://signed.example/doc");
    }

    @Test
    @DisplayName("R5.1: MANAGER is 403 on relink and delete (document untouched); FINANCIER relinks, ADMIN deletes")
    void relinkAndDeletePermissions() throws Exception {
        long project = createActiveProject("relink");
        long doc = createDocument(null, "WORKER", "rl" + runId);
        String body = "{\"projectId\":" + project + "}";

        assertThat(status(put("/api/company-purchases/documents/" + doc + "/project")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", bearer("MANAGER")))).isEqualTo(403);
        assertThat(status(delete("/api/company-purchases/documents/" + doc)
                .header("Authorization", bearer("MANAGER")))).isEqualTo(403);
        assertThat(projectOf(doc)).as("still no project after the denied relink").isNull();

        MvcResult relinked = mockMvc.perform(put("/api/company-purchases/documents/" + doc + "/project")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", bearer("FINANCIER"))).andReturn();
        assertThat(relinked.getResponse().getStatus()).as(relinked.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(projectOf(doc)).isEqualTo(project);

        assertThat(status(delete("/api/company-purchases/documents/" + doc)
                .header("Authorization", bearer("ADMIN")))).isEqualTo(204);
        assertThat(status(get("/api/company-purchases/documents/" + doc)
                .header("Authorization", bearer("ADMIN")))).isEqualTo(404);
    }

    // --- helpers -------------------------------------------------------------------------------

    private record Probe(String name, String operation, MockHttpServletRequestBuilder request,
                         int statusWhenAllowed) {
    }

    private List<Probe> probes() {
        String j = MediaType.APPLICATION_JSON_VALUE;
        return List.of(
                new Probe("GET documents", "READ", get("/api/company-purchases/documents"), 200),
                new Probe("GET lines", "READ", get("/api/company-purchases/lines"), 200),
                new Probe("GET missing document", "READ", get("/api/company-purchases/documents/" + MISSING), 404),
                new Probe("PUT relink missing", "UPDATE",
                        put("/api/company-purchases/documents/" + MISSING + "/project")
                                .contentType(j).content("{\"projectId\":1}"), 404),
                new Probe("POST reprocess missing", "UPDATE",
                        post("/api/company-purchases/documents/" + MISSING + "/reprocess"), 404),
                new Probe("DELETE missing", "DELETE", delete("/api/company-purchases/documents/" + MISSING), 404));
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private JsonNode body(MockHttpServletRequestBuilder request, String auth, int expected) throws Exception {
        MvcResult r = mockMvc.perform(request.header("Authorization", auth)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode list(String auth, String query) throws Exception {
        return body(get("/api/company-purchases/documents?" + query), auth, 200);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    private Long projectOf(long documentId) throws Exception {
        List<String> r = BankLedgerItSupport.queryForStrings(POSTGRES,
                "SELECT COALESCE(CAST(project_id AS text), '') FROM procurement_documents WHERE id = ?", documentId);
        return r.isEmpty() || r.get(0).isEmpty() ? null : Long.valueOf(r.get(0));
    }
}
