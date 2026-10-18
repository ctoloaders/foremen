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
 * HTTP client for the FOR-06-01 Work Reporting API detailed slice (FOR-QA-AUTO-06-01).
 *
 * <p>Mirrors {@link TeamApiHelper}: a thin Playwright-backed client bound to the API base URL,
 * authenticated as the seeded ADMIN (or any role for the ABAC cases). Every call returns a
 * {@link Resp} carrying the raw HTTP status plus a lazily-parsed JSON body, so a step can assert on
 * both the status code and the response fields.
 *
 * <p>The backend reports business errors as a localized {@code message} string with NO machine code
 * field, so tests assert on the HTTP status (the stable contract) and on response JSON fields for
 * the derivations (totalQuantity / revenueValue / resolved quantity).
 *
 * <p>Covered surface (design 4.2 + Amendment A1):
 * <ul>
 *   <li>{@code POST /api/work-reports} — create / submit (201), or fast-path approve.</li>
 *   <li>{@code GET /api/work-reports?query=project.id==&lt;id&gt;} — project-scoped list (200).</li>
 *   <li>{@code GET /api/work-reports/{id}} — read one (200).</li>
 *   <li>{@code PUT /api/work-reports/{id}} — edit / resubmit (200).</li>
 *   <li>{@code DELETE /api/work-reports/{id}} — delete (200/204).</li>
 *   <li>{@code POST /api/work-reports/{id}/approve|reject|request-changes} — review (200).</li>
 *   <li>{@code GET /api/work-reports/progress} — matrix progress projection (A1).</li>
 *   <li>{@code POST /api/work-reports/batch} — bulk create, all-or-nothing (A1).</li>
 * </ul>
 */
public final class WorkReportApiHelper implements AutoCloseable {

    private final Playwright playwright;
    private final APIRequestContext request;
    private String bearer;

    private WorkReportApiHelper(Playwright playwright, APIRequestContext request) {
        this.playwright = playwright;
        this.request = request;
    }

    public static WorkReportApiHelper create() {
        Playwright pw = Playwright.create();
        APIRequestContext req = pw.request().newContext(
                new APIRequest.NewContextOptions().setBaseURL(TestConfig.apiUrl()));
        return new WorkReportApiHelper(pw, req);
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

    // ---- Work reports ----

    /** Create a report from a raw body map (the step builds project/workItem/worker/dates/rooms). */
    public Resp create(Map<String, Object> body) {
        return post("/api/work-reports", auth().setData(body));
    }

    /** Project-scoped list via the FOR-04 query grammar (project.id==&lt;id&gt;). */
    public Resp list(long projectId) {
        return get("/api/work-reports",
                auth().setQueryParam("query", "project.id==" + projectId).setQueryParam("size", "200"));
    }

    /** List without a token (401 case). */
    public Resp listNoToken(long projectId) {
        RequestOptions opts = RequestOptions.create()
                .setQueryParam("query", "project.id==" + projectId);
        return Resp.from(request.get("/api/work-reports", opts));
    }

    public Resp readOne(long id) {
        return get("/api/work-reports/" + id, auth());
    }

    public Resp update(long id, Map<String, Object> body) {
        return Resp.from(request.put("/api/work-reports/" + id, auth().setData(body)));
    }

    public Resp delete(long id) {
        return Resp.from(request.delete("/api/work-reports/" + id, auth()));
    }

    public Resp approve(long id) {
        return post("/api/work-reports/" + id + "/approve", auth());
    }

    public Resp reject(long id, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) {
            body.put("reason", reason);
        }
        return post("/api/work-reports/" + id + "/reject", auth().setData(body));
    }

    public Resp requestChanges(long id, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) {
            body.put("reason", reason);
        }
        return post("/api/work-reports/" + id + "/request-changes", auth().setData(body));
    }

    // ---- Amendment A1: progress projection + bulk create ----

    /**
     * Read the matrix progress projection for a (project, worker) pair
     * (GET /api/work-reports/progress?projectId=&workerId=). The response body is the
     * WorkReportProgressDto (rooms[] column axis + categories[] -> workItems[] -> cells[]); a WORKER
     * caller may request only their own workerId (own-records).
     */
    public Resp progress(long projectId, long workerId) {
        return get("/api/work-reports/progress",
                auth().setQueryParam("projectId", String.valueOf(projectId))
                        .setQueryParam("workerId", String.valueOf(workerId)));
    }

    /** Progress projection WITHOUT a token (401 case). */
    public Resp progressNoToken(long projectId, long workerId) {
        RequestOptions opts = RequestOptions.create()
                .setQueryParam("projectId", String.valueOf(projectId))
                .setQueryParam("workerId", String.valueOf(workerId));
        return Resp.from(request.get("/api/work-reports/progress", opts));
    }

    /**
     * Bulk-create a batch of reports (POST /api/work-reports/batch) with the given item list wrapped
     * as { reports: [...] }. On full success returns the persisted ids
     * (WorkReportBatchResponse { created: [id] }); a single failing item aborts the whole batch
     * (all-or-nothing) and returns a 400 WorkReportBatchItemError ({ index, workItemId, roomId?, message }).
     */
    public Resp createBatch(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reports", items);
        return post("/api/work-reports/batch", auth().setData(body));
    }

    /** Bulk create WITHOUT a token (401 case). */
    public Resp createBatchNoToken(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reports", items);
        return Resp.from(request.post("/api/work-reports/batch", RequestOptions.create().setData(body)));
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

    /** A parsed HTTP response: status + raw text + (when parseable) a JSON element. */
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

        /** The {@code content} array of a Spring Data page response, or a bare array. */
        public JsonArray listContent() {
            if (json != null && json.isJsonArray()) {
                return json.getAsJsonArray();
            }
            JsonObject o = obj();
            if (o.has("content") && o.get("content").isJsonArray()) {
                return o.getAsJsonArray("content");
            }
            throw new IllegalStateException("Response is neither an array nor a page: " + text);
        }

        public String string(String field) {
            return str(obj(), field);
        }

        /** Find the first report row whose id matches, in a list/page body. */
        public JsonObject rowById(long id) {
            for (JsonElement el : listContent()) {
                JsonObject row = el.getAsJsonObject();
                if (row.has("id") && !row.get("id").isJsonNull() && row.get("id").getAsLong() == id) {
                    return row;
                }
            }
            return null;
        }

        /** The created id array of a WorkReportBatchResponse (bulk-create success body). */
        public JsonArray batchCreatedIds() {
            JsonObject o = obj();
            if (o.has("created") && o.get("created").isJsonArray()) {
                return o.getAsJsonArray("created");
            }
            throw new IllegalStateException("Response has no 'created' array: " + text);
        }

        /** The zero-based index of the failing item in a WorkReportBatchItemError 400 body. */
        public int batchErrorIndex() {
            JsonObject o = obj();
            if (!o.has("index") || o.get("index").isJsonNull()) {
                throw new IllegalStateException("Response has no batch-error 'index': " + text);
            }
            return o.get("index").getAsInt();
        }

        /**
         * The cells[] of the first work-item row matching workItemId across all category groups in a
         * WorkReportProgressDto, or null when that work item is absent (hidden because every planned
         * room is >=100%).
         */
        public JsonArray progressCellsFor(long workItemId) {
            JsonObject dto = obj();
            if (!dto.has("categories") || !dto.get("categories").isJsonArray()) {
                return null;
            }
            for (JsonElement cat : dto.getAsJsonArray("categories")) {
                JsonArray items = cat.getAsJsonObject().getAsJsonArray("workItems");
                if (items == null) {
                    continue;
                }
                for (JsonElement item : items) {
                    JsonObject row = item.getAsJsonObject();
                    if (row.has("workItemId") && row.get("workItemId").getAsLong() == workItemId) {
                        return row.getAsJsonArray("cells");
                    }
                }
            }
            return null;
        }

        /** The cell for (workItemId, roomId) in a progress projection, or null. */
        public JsonObject progressCell(long workItemId, long roomId) {
            JsonArray cells = progressCellsFor(workItemId);
            if (cells == null) {
                return null;
            }
            for (JsonElement c : cells) {
                JsonObject cell = c.getAsJsonObject();
                if (cell.has("roomId") && cell.get("roomId").getAsLong() == roomId) {
                    return cell;
                }
            }
            return null;
        }

        /** true when the progress projection contains a row for workItemId. */
        public boolean progressHasWorkItem(long workItemId) {
            return progressCellsFor(workItemId) != null;
        }

        /** The number of column rooms (rooms[]) in a progress projection. */
        public int progressRoomCount() {
            JsonObject dto = obj();
            if (dto.has("rooms") && dto.get("rooms").isJsonArray()) {
                return dto.getAsJsonArray("rooms").size();
            }
            return 0;
        }

        public int size() {
            return listContent().size();
        }
    }
}
