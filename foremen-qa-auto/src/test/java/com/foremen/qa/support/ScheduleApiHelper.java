package com.foremen.qa.support;

import java.util.LinkedHashMap;
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
 * HTTP client for the FOR-05-10 Planning-Gantt ({@code Schedule_API}) detailed slice
 * (FOR-QA-AUTO-10).
 *
 * <p>Mirrors {@link TeamApiHelper}: a thin Playwright-backed client bound to the API base URL,
 * authenticated as the seeded ADMIN (or any role for ABAC/scoping checks). Each call returns a
 * {@link Resp} with the raw HTTP status plus a lazily-parsed JSON body so a step can assert both.
 *
 * <p>Covered surface:
 * <ul>
 *   <li>{@code GET /api/project-schedules?projectId=} — the {@code Schedule_View} (200).</li>
 *   <li>{@code GET /api/project-schedules/readiness?projectId=} — the {@code schedule} gate (200).</li>
 *   <li>{@code PUT /api/project-schedules/bars?projectId=} — save bars (200) / conflict (409).</li>
 *   <li>{@code POST /api/project-schedules/auto-create?projectId=} — auto-create (200).</li>
 * </ul>
 * It also wraps the member/worker-type helpers the Gantt setup needs (reusing the Team API) so a
 * step can seed an ACTIVE crew and derive the row category ids the schedule exposes.
 */
public final class ScheduleApiHelper implements AutoCloseable {

    private final Playwright playwright;
    private final APIRequestContext request;
    private String bearer;

    private ScheduleApiHelper(Playwright playwright, APIRequestContext request) {
        this.playwright = playwright;
        this.request = request;
    }

    public static ScheduleApiHelper create() {
        Playwright pw = Playwright.create();
        APIRequestContext req = pw.request().newContext(
                new APIRequest.NewContextOptions().setBaseURL(TestConfig.apiUrl()));
        return new ScheduleApiHelper(pw, req);
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

    public void clearAuth() {
        this.bearer = null;
    }

    public String bearer() {
        return bearer;
    }

    // ---- Schedule view / readiness ----

    public Resp view(long projectId) {
        return view(projectId, null);
    }

    public Resp view(long projectId, String acceptLanguage) {
        RequestOptions opts = auth().setQueryParam("projectId", String.valueOf(projectId));
        if (acceptLanguage != null) {
            opts.setHeader("Accept-Language", acceptLanguage);
        }
        return Resp.from(request.get("/api/project-schedules", opts));
    }

    public Resp readiness(long projectId) {
        return Resp.from(request.get("/api/project-schedules/readiness",
                auth().setQueryParam("projectId", String.valueOf(projectId))));
    }

    public Resp autoCreate(long projectId, long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        return Resp.from(request.post("/api/project-schedules/auto-create",
                auth().setQueryParam("projectId", String.valueOf(projectId)).setData(body)));
    }

    // ---- Member helpers reused from the Team API ----

    public Resp listMembers(long projectId) {
        return Resp.from(request.get("/api/project-members",
                auth().setQueryParam("projectId", String.valueOf(projectId))));
    }

    public Resp assignWorker(long userId, long projectId, long workerRoleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("projectRoleId", workerRoleId);
        return Resp.from(request.post("/api/project-members", auth().setData(body)));
    }

    public Resp patchAssignmentStatus(long userId, long projectId, String assignmentStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("assignmentStatus", assignmentStatus);
        return Resp.from(request.patch("/api/project-members", auth().setData(body)));
    }

    // ---- Generic ----

    @Override
    public void close() {
        try {
            request.dispose();
        } finally {
            playwright.close();
        }
    }

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

    /** A parsed HTTP response: status + raw text + (when it parses) the JSON element. */
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

        public long versionField() {
            return lng(obj(), "version");
        }

        public int crewSizeField() {
            return (int) lng(obj(), "crewSize");
        }

        public JsonArray rows() {
            JsonObject o = obj();
            if (!o.has("rows") || !o.get("rows").isJsonArray()) {
                throw new IllegalStateException("Schedule view has no rows array: " + text);
            }
            return o.getAsJsonArray("rows");
        }

        /** The work-category ids of the schedule rows, in response order. */
        public java.util.List<Long> rowCategoryIds() {
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (JsonElement el : rows()) {
                ids.add(lng(el.getAsJsonObject(), "workCategoryId"));
            }
            return ids;
        }

        public String string(String field) {
            return str(obj(), field);
        }

        public int size() {
            return json != null && json.isJsonArray() ? json.getAsJsonArray().size() : 0;
        }
    }
}
