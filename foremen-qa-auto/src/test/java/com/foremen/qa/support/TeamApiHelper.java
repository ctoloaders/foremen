package com.foremen.qa.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;

/**
 * HTTP client for the FOR-05-09 Team (project-members) API detailed slice (FOR-QA-AUTO-09).
 *
 * <p>Mirrors {@link DocumentSigningApiHelper}: a thin Playwright-backed client bound to the API
 * base URL, authenticated as the seeded ADMIN (or any role for the ABAC cases). Every call returns
 * a {@link Resp} carrying the raw HTTP status plus a lazily-parsed JSON body, so a step can assert
 * on both the status code and the response fields.
 *
 * <p>The backend reports business errors as a localized {@code message} string with NO machine
 * code field (the {@code error} field holds the HTTP reason phrase). Tests therefore assert on the
 * HTTP status, which is the stable contract — never on a message code.
 *
 * <p>Covered surface:
 * <ul>
 *   <li>{@code POST /api/project-members} — assign (201).</li>
 *   <li>{@code PATCH /api/project-members} — attribute update: assignmentStatus / workerTypeId /
 *       tags (200).</li>
 *   <li>{@code DELETE /api/project-members?userId=&projectId=} — remove (204).</li>
 *   <li>{@code GET /api/project-members?projectId=} — list of {@code TeamMemberView} (200).</li>
 *   <li>{@code GET /api/project-members/candidates} — paginated candidates (200).</li>
 *   <li>{@code GET /api/project-members/projects?userId=} — ascending project ids (200).</li>
 *   <li>{@code GET /api/project-members/readiness?projectId=} — the {@code team} gate (200).</li>
 *   <li>{@code POST /api/users/client} — client registration + membership (201).</li>
 *   <li>{@code POST /api/users/worker} — worker record flow (201).</li>
 *   <li>{@code POST /api/users/worker/{id}/invite} — worker invitation (200).</li>
 *   <li>{@code GET /api/worker-types} — seeded worker types (200).</li>
 * </ul>
 */
public final class TeamApiHelper implements AutoCloseable {

    private final Playwright playwright;
    private final APIRequestContext request;
    private String bearer;

    private TeamApiHelper(Playwright playwright, APIRequestContext request) {
        this.playwright = playwright;
        this.request = request;
    }

    public static TeamApiHelper create() {
        Playwright pw = Playwright.create();
        APIRequestContext req = pw.request().newContext(
                new APIRequest.NewContextOptions().setBaseURL(TestConfig.apiUrl()));
        return new TeamApiHelper(pw, req);
    }

    // ---- Auth ----

    public String loginAs(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        APIResponse response = request.post("/api/auth/login", RequestOptions.create().setData(body));
        if (!response.ok()) {
            throw new IllegalStateException(
                    "login failed: HTTP " + response.status() + " " + safeBody(response));
        }
        this.bearer = str(JsonParser.parseString(response.text()).getAsJsonObject(), "accessToken");
        return bearer;
    }

    public String loginAdmin() {
        return loginAs(TestConfig.adminEmail(), TestConfig.adminPassword());
    }

    /** Drop the bearer so the next call is unauthenticated (for 401 cases). */
    public void clearAuth() {
        this.bearer = null;
    }

    public String bearer() {
        return bearer;
    }

    // ---- Reference data ----

    /** The seeded worker types, as a parsed page content array. */
    public Resp listWorkerTypes() {
        return get("/api/worker-types", auth().setQueryParam("size", "200"));
    }

    /** Resolve an active worker type id by its code (e.g. {@code BASE}) from GET /api/worker-types. */
    public long resolveWorkerTypeId(String code) {
        Resp resp = listWorkerTypes();
        JsonArray content = resp.pageContent();
        for (JsonElement el : content) {
            JsonObject row = el.getAsJsonObject();
            if (code.equalsIgnoreCase(str(row, "code"))) {
                return lng(row, "id");
            }
        }
        throw new IllegalStateException("No worker type with code " + code + " in " + resp.text());
    }

    // ---- Project members ----

    public Resp assign(long userId, long projectId, Long projectRoleId, Long workerTypeId,
                       List<String> tags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        if (projectRoleId != null) {
            body.put("projectRoleId", projectRoleId);
        }
        if (workerTypeId != null) {
            body.put("workerTypeId", workerTypeId);
        }
        if (tags != null) {
            body.put("tags", tags);
        }
        return post("/api/project-members", auth().setData(body));
    }

    public Resp assign(long userId, long projectId) {
        return assign(userId, projectId, null, null, null);
    }

    /** Assign with a raw body map (for malformed-input validation cases). */
    public Resp assignRaw(Map<String, Object> body) {
        return post("/api/project-members", auth().setData(body));
    }

    /** PATCH an assignment status change (deactivate / reactivate). */
    public Resp patchStatus(long userId, long projectId, String assignmentStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("assignmentStatus", assignmentStatus);
        return patch(body);
    }

    /** PATCH a worker-type set / replace. */
    public Resp patchWorkerType(long userId, long projectId, Long workerTypeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("workerTypeId", workerTypeId);
        return patch(body);
    }

    /** PATCH a tag replace. */
    public Resp patchTags(long userId, long projectId, List<String> tags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("tags", tags);
        return patch(body);
    }

    public Resp patch(Map<String, Object> body) {
        APIResponse response = request.patch("/api/project-members", auth().setData(body));
        return Resp.from(response);
    }

    public Resp remove(long userId, long projectId) {
        APIResponse response = request.delete("/api/project-members",
                auth().setQueryParam("userId", String.valueOf(userId))
                        .setQueryParam("projectId", String.valueOf(projectId)));
        return Resp.from(response);
    }

    /** Remove with raw query params (for identifier-validation cases). */
    public Resp removeRaw(String userId, String projectId) {
        RequestOptions opts = auth();
        if (userId != null) {
            opts.setQueryParam("userId", userId);
        }
        if (projectId != null) {
            opts.setQueryParam("projectId", projectId);
        }
        return Resp.from(request.delete("/api/project-members", opts));
    }

    public Resp listMembers(long projectId) {
        return listMembers(projectId, null);
    }

    public Resp listMembers(long projectId, String acceptLanguage) {
        RequestOptions opts = auth().setQueryParam("projectId", String.valueOf(projectId));
        if (acceptLanguage != null) {
            opts.setHeader("Accept-Language", acceptLanguage);
        }
        return Resp.from(request.get("/api/project-members", opts));
    }

    /** List members with a raw projectId query value (for validation cases); omit when null. */
    public Resp listMembersRaw(String projectId) {
        RequestOptions opts = auth();
        if (projectId != null) {
            opts.setQueryParam("projectId", projectId);
        }
        return Resp.from(request.get("/api/project-members", opts));
    }

    public Resp candidates(long projectId, Map<String, String> params) {
        RequestOptions opts = auth().setQueryParam("projectId", String.valueOf(projectId));
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                opts.setQueryParam(e.getKey(), e.getValue());
            }
        }
        return get("/api/project-members/candidates", opts);
    }

    public Resp listProjects(long userId) {
        return get("/api/project-members/projects",
                auth().setQueryParam("userId", String.valueOf(userId)));
    }

    public Resp readiness(long projectId) {
        return get("/api/project-members/readiness",
                auth().setQueryParam("projectId", String.valueOf(projectId)));
    }

    public Resp readinessRaw(String projectId) {
        RequestOptions opts = auth();
        if (projectId != null) {
            opts.setQueryParam("projectId", projectId);
        }
        return get("/api/project-members/readiness", opts);
    }

    // ---- Client / worker flows ----

    public Resp registerClient(String name, String email, long projectId, List<String> tags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("email", email);
        body.put("projectId", projectId);
        if (tags != null) {
            body.put("tags", tags);
        }
        return post("/api/users/client", auth().setData(body));
    }

    public Resp registerClientRaw(Map<String, Object> body) {
        return post("/api/users/client", auth().setData(body));
    }

    public Resp registerWorker(Map<String, Object> body) {
        return post("/api/users/worker", auth().setData(body));
    }

    public Resp inviteWorker(long userId) {
        return post("/api/users/worker/" + userId + "/invite", auth());
    }

    // ---- Project status (for locked-project setup) ----

    /**
     * Force a project into a given lifecycle status via {@code PUT /api/projects/{id}}. The generic
     * update requires {@code name} (NotBlank), so the current name must be resent. Used to transition
     * an editable project into a Locked_Status (COMPLETED / CANCELLED) for the lock test cases, since
     * the create endpoint itself rejects a locked status with the team-locked error.
     */
    public Resp updateProjectStatus(long projectId, String name, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("status", status);
        APIResponse response = request.put("/api/projects/" + projectId, auth().setData(body));
        return Resp.from(response);
    }

    // ---- Generic verbs ----

    private Resp get(String path, RequestOptions opts) {
        return Resp.from(request.get(path, opts));
    }

    private Resp post(String path, RequestOptions opts) {
        return Resp.from(request.post(path, opts));
    }

    @Override
    public void close() {
        try {
            request.dispose();
        } finally {
            playwright.close();
        }
    }

    // ---- internals ----

    private RequestOptions auth() {
        RequestOptions opts = RequestOptions.create();
        if (bearer != null) {
            opts.setHeader("Authorization", "Bearer " + bearer);
        }
        return opts;
    }

    private static String safeBody(APIResponse response) {
        try {
            return response.text();
        } catch (RuntimeException e) {
            return "<no body>";
        }
    }

    public static String str(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : null;
    }

    public static long lng(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new IllegalStateException("Expected numeric " + field + " in: " + json);
        }
        return json.get(field).getAsLong();
    }

    /**
     * A parsed HTTP response: the status plus the body kept both as raw text and (when it parses) as
     * a JSON element. Helpers expose the common shapes the Team API returns — a JSON array (member
     * list / project-id list), a single object (view / readiness / error), or a Spring page.
     */
    public record Resp(int status, String text, JsonElement json) {

        static Resp from(APIResponse response) {
            int status = response.status();
            String text;
            try {
                text = response.text();
            } catch (RuntimeException e) {
                text = "";
            }
            JsonElement json = null;
            if (text != null && !text.isBlank()) {
                try {
                    json = JsonParser.parseString(text);
                } catch (RuntimeException ignored) {
                    // non-JSON body (e.g. empty 204); leave json null
                }
            }
            return new Resp(status, text == null ? "" : text, json);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonObject obj() {
            if (json == null || !json.isJsonObject()) {
                throw new IllegalStateException("Response body is not a JSON object: " + text);
            }
            return json.getAsJsonObject();
        }

        public JsonArray array() {
            if (json == null || !json.isJsonArray()) {
                throw new IllegalStateException("Response body is not a JSON array: " + text);
            }
            return json.getAsJsonArray();
        }

        /** The {@code content} array of a Spring Data page response. */
        public JsonArray pageContent() {
            JsonObject o = obj();
            if (!o.has("content") || !o.get("content").isJsonArray()) {
                throw new IllegalStateException("Response is not a paginated page: " + text);
            }
            return o.getAsJsonArray("content");
        }

        public long totalElements() {
            return lng(obj(), "totalElements");
        }

        public String string(String field) {
            return str(obj(), field);
        }

        /** Find the first member view object in a list response whose userId matches. */
        public JsonObject memberByUserId(long userId) {
            for (JsonElement el : array()) {
                JsonObject row = el.getAsJsonObject();
                if (row.has("userId") && !row.get("userId").isJsonNull()
                        && row.get("userId").getAsLong() == userId) {
                    return row;
                }
            }
            return null;
        }

        public int size() {
            return json != null && json.isJsonArray() ? json.getAsJsonArray().size() : 0;
        }
    }
}
