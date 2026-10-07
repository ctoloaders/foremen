package com.foremen.qa.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;

/**
 * Thin HTTP client used for scenario <b>setup and teardown</b>, and for asserting UI-invisible
 * contracts a UI scenario depends on (Requirements 2.3, 3.2, 3.3).
 *
 * <p>It wraps a Playwright {@link APIRequestContext} pointed at the API base URL
 * ({@link TestConfig#apiUrl()}), reusing Playwright rather than adding a separate HTTP dependency.
 * After {@link #loginAdmin()} the bearer token is attached to every subsequent request so the
 * helper acts as the seeded ADMIN — the identity that can create/delete roles, dictionary rows, and
 * projects for a scenario's fixture.
 *
 * <p>This helper does <b>not</b> provision UI-login users; that is done via direct DB insert by the
 * test-user fixture (task 3a). The methods here cover exactly the setup/teardown surface the smoke
 * scenarios need:
 * <ul>
 *   <li>{@link #loginAdmin()} / {@link #login(String, String)} — {@code POST /api/auth/login}.</li>
 *   <li>{@link #createRole(String)} / {@link #setRolePermissions(long, List)} /
 *       {@link #deleteRole(long)} — restricted-role setup for menu-visibility (Requirement 6.4).</li>
 *   <li>{@link #createDictionaryRow(String, Object)} / {@link #deleteDictionaryRow(String, long)} —
 *       generic dictionary create/teardown.</li>
 *   <li>{@link #createProject(Object)} / {@link #deleteProject(long)} — project create/teardown.</li>
 * </ul>
 *
 * <p>Every mutating call verifies the HTTP status and fails fast with the response body on error, so
 * a broken fixture surfaces immediately rather than as a confusing downstream UI failure. All
 * teardown deletes are idempotent from the caller's perspective (a {@code 404} on delete is treated
 * as already-gone, so LIFO cleanup converges even after a partial failure).
 */
public final class ApiHelper implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Playwright playwright;
    private final APIRequestContext request;

    private String accessToken;
    private String refreshToken;

    private ApiHelper(Playwright playwright, APIRequestContext request) {
        this.playwright = playwright;
        this.request = request;
    }

    /**
     * Create a helper with its own Playwright + request context bound to the API base URL. The
     * caller owns the returned helper and must {@link #close()} it (the scenario {@code World} does
     * this in the {@code @After} hook).
     */
    public static ApiHelper create() {
        Playwright playwright = Playwright.create();
        APIRequestContext request = playwright.request().newContext(
                new APIRequest.NewContextOptions()
                        .setBaseURL(TestConfig.apiUrl()));
        return new ApiHelper(playwright, request);
    }

    // ---- Authentication (Requirement 2.3) ----

    /**
     * Authenticate as the seeded ADMIN using {@link TestConfig#adminEmail()}/
     * {@link TestConfig#adminPassword()} and attach the resulting bearer token to subsequent
     * requests.
     *
     * @return the access token (also stored internally as the default bearer)
     */
    public String loginAdmin() {
        return login(TestConfig.adminEmail(), TestConfig.adminPassword());
    }

    /**
     * Authenticate as an arbitrary user via {@code POST /api/auth/login}, storing both tokens and
     * using the access token as the bearer for later calls.
     *
     * @return the access token
     */
    public String login(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        APIResponse response = request.post("/api/auth/login",
                RequestOptions.create().setData(body));
        JsonObject json = okJson(response, "POST /api/auth/login");
        this.accessToken = getString(json, "accessToken");
        this.refreshToken = getString(json, "refreshToken");
        return accessToken;
    }

    /** The access token from the most recent successful login, or {@code null} if not logged in. */
    public String accessToken() {
        return accessToken;
    }

    /** The refresh token from the most recent successful login, or {@code null} if not logged in. */
    public String refreshToken() {
        return refreshToken;
    }

    // ---- Resource / operation id lookup (Requirement 6.4) ----

    /**
     * Resolve a resource's numeric id from its {@code code} via {@code GET /api/resources}. Resource
     * ids are database-generated (BIGSERIAL) so a run cannot assume a fixed value; the restricted
     * role in menu-visibility grants {@code USERS:READ}, so the resource id must be looked up at
     * run time. Requires a prior {@link #loginAdmin()}.
     *
     * @param code the resource code (e.g. {@code USERS})
     * @return the resource id
     * @throws IllegalStateException if no resource with that code exists
     */
    public long resolveResourceId(String code) {
        return resolveIdByCode("/api/resources", code, "resource");
    }

    /**
     * Resolve an operation's numeric id from its {@code code} (e.g. {@code READ}) via
     * {@code GET /api/operations}. Operation ids are database-generated, so — like resources — they
     * are looked up at run time rather than hard-coded. Requires a prior {@link #loginAdmin()}.
     *
     * @param code the operation code (e.g. {@code READ})
     * @return the operation id
     * @throws IllegalStateException if no operation with that code exists
     */
    public long resolveOperationId(String code) {
        return resolveIdByCode("/api/operations", code, "operation");
    }

    /**
     * Resolve a dictionary row's numeric id from its {@code code} on any paginated dictionary list
     * endpoint (Spring {@code Page<{id, code, ...}>}), e.g. a seeded currency id by code
     * ({@code PLN}) via {@code /api/currencies}. Used by the work-prices setup to reference a seeded
     * currency without hard-coding its database-generated id. Requires a prior {@link #loginAdmin()}.
     *
     * @param resourcePath the collection path, e.g. {@code /api/currencies}
     * @param code the row {@code code} to match (case-insensitive)
     * @return the matching row id
     * @throws IllegalStateException if no row with that code exists
     */
    public long resolveDictionaryIdByCode(String resourcePath, String code) {
        return resolveIdByCode(resourcePath, code, "dictionary row");
    }

    /**
     * Resolve a row's numeric id from its exact {@code name} on any paginated list endpoint that
     * supports the RSQL {@code query} filter and projects a {@code name} field (e.g. work items via
     * {@code /api/work-items}, projects via {@code /api/projects}). Uses the {@code name==<name>}
     * RSQL filter to narrow the page, then matches {@code name} exactly (case-insensitive) in the
     * returned {@code content}. Used to resolve UI-created rows for teardown when only their name is
     * known. Requires a prior {@link #loginAdmin()}.
     *
     * @param resourcePath the collection path, e.g. {@code /api/work-items}
     * @param name the row {@code name} to match
     * @return the matching row id
     * @throws IllegalStateException if no row with that name exists
     */
    public long resolveIdByName(String resourcePath, String name) {
        APIResponse response = request.get(resourcePath,
                withAuth().setQueryParam("query", "name==" + name).setQueryParam("size", "50"));
        JsonObject json = okJson(response, "GET " + resourcePath);
        if (!json.has("content") || !json.get("content").isJsonArray()) {
            throw new IllegalStateException(
                    "GET " + resourcePath + " did not return a paginated content array: " + json);
        }
        for (var element : json.getAsJsonArray("content")) {
            JsonObject row = element.getAsJsonObject();
            if (name.equalsIgnoreCase(getString(row, "name"))) {
                return getLong(row, "id");
            }
        }
        throw new IllegalStateException(
                "No row found with name '" + name + "' via " + resourcePath);
    }

    /**
     * Look up the id of a read-only reference row by its {@code code} on a paginated admin list
     * endpoint (Spring {@code Page<{id, code, ...}>}). Uses the endpoint's {@code query} filter to
     * narrow the page, then matches {@code code} exactly (case-insensitive) in the returned
     * {@code content}.
     */
    private long resolveIdByCode(String path, String code, String label) {
        // The backend's list endpoints filter via a `field<op>value` query expression (see
        // QueryParser/QueryTokenizer). A bare code has no operator, so the parser rejects it with
        // HTTP 400 ("unexpected character at position 0"). Use the `code==<value>` equality filter,
        // mirroring resolveIdByName's `name==<value>`; the exact match is still verified below.
        APIResponse response = request.get(path,
                withAuth().setQueryParam("query", "code==" + code).setQueryParam("size", "200"));
        JsonObject json = okJson(response, "GET " + path);
        if (!json.has("content") || !json.get("content").isJsonArray()) {
            throw new IllegalStateException(
                    "GET " + path + " did not return a paginated content array: " + json);
        }
        for (var element : json.getAsJsonArray("content")) {
            JsonObject row = element.getAsJsonObject();
            if (code.equalsIgnoreCase(getString(row, "code"))) {
                return getLong(row, "id");
            }
        }
        throw new IllegalStateException(
                "No " + label + " found with code '" + code + "' via " + path);
    }

    // ---- Users (teardown for the admin-panel create scenario, Requirement 7.3) ----

    /**
     * Resolve a user's numeric id from its {@code email} via {@code GET /api/users}. Users created
     * through the UI carry a run-unique generated email, so the id must be looked up at run time to
     * deactivate the user in teardown. Uses the list's RSQL {@code query} filter ({@code email==...})
     * to narrow the page, then matches {@code email} exactly (case-insensitive) in the returned
     * {@code content}. Requires a prior {@link #loginAdmin()}.
     *
     * @param email the user's email
     * @return the user id
     * @throws IllegalStateException if no user with that email exists
     */
    public long resolveUserIdByEmail(String email) {
        APIResponse response = request.get("/api/users",
                withAuth().setQueryParam("query", "email==" + email).setQueryParam("size", "50"));
        JsonObject json = okJson(response, "GET /api/users");
        if (!json.has("content") || !json.get("content").isJsonArray()) {
            throw new IllegalStateException(
                    "GET /api/users did not return a paginated content array: " + json);
        }
        for (var element : json.getAsJsonArray("content")) {
            JsonObject row = element.getAsJsonObject();
            if (email.equalsIgnoreCase(getString(row, "email"))) {
                return getLong(row, "id");
            }
        }
        throw new IllegalStateException(
                "No user found with email '" + email + "' via GET /api/users");
    }

    /**
     * Deactivate (soft-delete) a user via {@code DELETE /api/users/{id}} — the same endpoint the UI
     * uses. Idempotent: a {@code 404} is treated as already-gone so LIFO teardown never fails on an
     * already-clean state (Requirement 7.3, 3.2). Requires a prior {@link #loginAdmin()}.
     *
     * @param userId the user id to deactivate
     */
    public void deactivateUser(long userId) {
        String path = "/api/users/" + userId;
        APIResponse response = request.delete(path, withAuth());
        expectDeleted(response, "DELETE " + path);
    }

    // ---- Roles (Requirement 6.4) ----

    /**
     * Create a role via {@code POST /api/roles} with the given code and derived names, returning its
     * generated id. Requires a prior {@link #loginAdmin()}.
     *
     * @param code the role code (e.g. a run-unique {@code TESTROLE_*})
     * @return the created role id
     */
    public long createRole(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("nameRU", "QA " + code);
        body.put("namePL", "QA " + code);
        body.put("descriptionRU", "QA test role " + code);
        body.put("descriptionPL", "QA test role " + code);
        APIResponse response = request.post("/api/roles", withAuth().setData(body));
        JsonObject json = okJson(response, "POST /api/roles");
        return getLong(json, "id");
    }

    /**
     * Replace a role's permission matrix via {@code PUT /api/roles/{id}/permissions}. Each entry
     * grants a resource the listed operations (by numeric ids), matching the backend
     * {@code RolePermissionRequest{permissions:[{resourceId, operationIds:[]}]}} contract.
     *
     * @param roleId the role to update
     * @param entries the resource → operation-ids grants
     */
    public void setRolePermissions(long roleId, List<PermissionEntry> entries) {
        List<Map<String, Object>> permissions = entries.stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("resourceId", e.resourceId());
                    m.put("operationIds", e.operationIds());
                    return m;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permissions", permissions);
        APIResponse response = request.put("/api/roles/" + roleId + "/permissions",
                withAuth().setData(body));
        expectOk(response, "PUT /api/roles/" + roleId + "/permissions");
    }

    /**
     * Idempotently GRANT a single {@code (resource, operation)} to a role via the roles admin API,
     * preserving every other grant the role already holds. This replicates the roles admin-panel
     * action and persists in the DB (no seed changeset): a {@code PUT /api/roles/{id}/permissions}
     * REPLACES the whole matrix, so the current matrix is first read via
     * {@code GET /api/roles/{id}/permissions}, the target operation merged into the target resource's
     * operation set (adding the resource entry if the role did not grant it at all), and the merged
     * matrix PUT back.
     *
     * <p>Resource/role/operation ids are database-generated, so they are resolved at run time from
     * their codes. Idempotent: if the operation is already present the merged set is identical and the
     * PUT is a harmless no-op, so re-runs are safe. Requires a prior {@link #loginAdmin()}.
     *
     * @param roleCode      the role code to grant on (e.g. {@code CLIENT})
     * @param resourceCode  the resource code (e.g. {@code OFFERS})
     * @param operationCode the operation code to add (e.g. {@code UPDATE})
     */
    public void grantRoleOperation(String roleCode, String resourceCode, String operationCode) {
        long roleId = resolveRoleIdByCode(roleCode);
        long resourceId = resolveResourceId(resourceCode);
        long operationId = resolveOperationId(operationCode);
        Map<Long, List<Long>> matrix = currentRoleMatrix(roleId);
        List<Long> ops = matrix.computeIfAbsent(resourceId, k -> new ArrayList<>());
        if (!ops.contains(operationId)) {
            ops.add(operationId);
        }
        putRoleMatrix(roleId, matrix);
    }

    /**
     * Idempotently REVOKE a single {@code (resource, operation)} from a role via the roles admin API,
     * preserving every other grant — the inverse of {@link #grantRoleOperation}. Used by a scenario's
     * teardown to restore a mutated shared system role to its original matrix. Best-effort/idempotent:
     * if the operation (or resource) is already absent the matrix is PUT back unchanged. If removing
     * the operation empties a resource's operation set the resource entry is dropped entirely (the
     * backend treats a resource with no operations as no grant). Requires a prior {@link #loginAdmin()}.
     *
     * @param roleCode      the role code to revoke on (e.g. {@code CLIENT})
     * @param resourceCode  the resource code (e.g. {@code OFFERS})
     * @param operationCode the operation code to remove (e.g. {@code UPDATE})
     */
    public void revokeRoleOperation(String roleCode, String resourceCode, String operationCode) {
        long roleId = resolveRoleIdByCode(roleCode);
        long resourceId = resolveResourceId(resourceCode);
        long operationId = resolveOperationId(operationCode);
        Map<Long, List<Long>> matrix = currentRoleMatrix(roleId);
        List<Long> ops = matrix.get(resourceId);
        if (ops != null) {
            ops.remove(operationId);
            if (ops.isEmpty()) {
                matrix.remove(resourceId);
            }
        }
        putRoleMatrix(roleId, matrix);
    }

    /**
     * Read a role's current permission matrix via {@code GET /api/roles/{id}/permissions} and map it
     * down to the PUT request shape: {@code resourceId -> [operationId...]}. The GET response
     * ({@code RolePermissionResponse}) carries richer objects
     * ({@code permissions[].{resourceId, resourceCode, operations[].operationId}}); only the ids are
     * kept so the result can be PUT back as {@code RolePermissionRequest}. Insertion order is
     * preserved (LinkedHashMap) so a round-trip without edits re-PUTs an equivalent matrix.
     */
    private Map<Long, List<Long>> currentRoleMatrix(long roleId) {
        APIResponse response = request.get("/api/roles/" + roleId + "/permissions", withAuth());
        JsonObject json = okJson(response, "GET /api/roles/" + roleId + "/permissions");
        Map<Long, List<Long>> matrix = new LinkedHashMap<>();
        if (json.has("permissions") && json.get("permissions").isJsonArray()) {
            for (JsonElement permEl : json.getAsJsonArray("permissions")) {
                JsonObject perm = permEl.getAsJsonObject();
                long resourceId = getLong(perm, "resourceId");
                List<Long> ops = new ArrayList<>();
                if (perm.has("operations") && perm.get("operations").isJsonArray()) {
                    for (JsonElement opEl : perm.getAsJsonArray("operations")) {
                        ops.add(getLong(opEl.getAsJsonObject(), "operationId"));
                    }
                }
                matrix.put(resourceId, ops);
            }
        }
        return matrix;
    }

    /**
     * Replace a role's permission matrix via {@code PUT /api/roles/{id}/permissions} from the
     * {@code resourceId -> [operationId...]} map, serializing each entry as the backend
     * {@code PermissionEntryRequest{resourceId, operationIds}}. The PUT is per-role (it never touches
     * any other role) and the backend validates every resource/operation id exists. Expects 2xx.
     */
    private void putRoleMatrix(long roleId, Map<Long, List<Long>> matrix) {
        List<Map<String, Object>> permissions = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> entry : matrix.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceId", entry.getKey());
            m.put("operationIds", entry.getValue());
            permissions.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permissions", permissions);
        APIResponse response = request.put("/api/roles/" + roleId + "/permissions",
                withAuth().setData(body));
        expectOk(response, "PUT /api/roles/" + roleId + "/permissions");
    }

    /**
     * Delete a role via {@code DELETE /api/roles/{id}}. Idempotent: a {@code 404} is treated as
     * already-deleted so LIFO teardown never fails on an already-clean state.
     */
    public void deleteRole(long roleId) {
        APIResponse response = request.delete("/api/roles/" + roleId, withAuth());
        expectDeleted(response, "DELETE /api/roles/" + roleId);
    }

    // ---- Dictionaries (generic create/teardown) ----

    /**
     * Create a dictionary row via {@code POST {resourcePath}} with a caller-supplied body (a
     * {@link Map} or record; serialized to JSON), returning the generated id from the response.
     * The generic CRUD create returns HTTP 200 with a body carrying {@code id}.
     *
     * @param resourcePath the collection path, e.g. {@code /api/measurement-units}
     * @param body the create request body (shape depends on the dictionary)
     * @return the created row id
     */
    public long createDictionaryRow(String resourcePath, Object body) {
        APIResponse response = request.post(resourcePath, withAuth().setData(body));
        JsonObject json = okJson(response, "POST " + resourcePath);
        return getLong(json, "id");
    }

    /**
     * Delete a dictionary row via {@code DELETE {resourcePath}/{id}}. Idempotent on {@code 404}.
     *
     * @param resourcePath the collection path, e.g. {@code /api/measurement-units}
     * @param id the row id to delete
     */
    public void deleteDictionaryRow(String resourcePath, long id) {
        String path = resourcePath + "/" + id;
        APIResponse response = request.delete(path, withAuth());
        expectDeleted(response, "DELETE " + path);
    }

    // ---- Projects (create/teardown) ----

    /**
     * Create a project via the custom {@code POST /api/projects} endpoint (returns HTTP 201) with a
     * caller-supplied body, returning the generated id.
     *
     * @param body the {@code CreateProjectRequest}-shaped body (Map or record)
     * @return the created project id
     */
    public long createProject(Object body) {
        APIResponse response = request.post("/api/projects", withAuth().setData(body));
        JsonObject json = okJson(response, "POST /api/projects");
        return getLong(json, "id");
    }

    /**
     * Delete a project via {@code DELETE /api/projects/{id}}. Idempotent on {@code 404}.
     */
    public void deleteProject(long id) {
        String path = "/api/projects/" + id;
        APIResponse response = request.delete(path, withAuth());
        expectDeleted(response, "DELETE " + path);
    }

    // ---- Roles (project-member role resolution, FOR-QA-AUTO-05) ----

    /**
     * Resolve a role's numeric id from its {@code code} via {@code GET /api/roles} (a paginated
     * {@code Page<{id, code, ...}>}). Role ids are database-generated, so the CLIENT project-role id
     * used in a project-member assignment must be looked up at run time rather than hard-coded.
     * Thin public wrapper over the shared code-lookup. Requires a prior {@link #loginAdmin()}.
     *
     * @param code the role code (e.g. {@code CLIENT})
     * @return the role id
     * @throws IllegalStateException if no role with that code exists
     */
    public long resolveRoleIdByCode(String code) {
        return resolveIdByCode("/api/roles", code, "role");
    }

    // ---- Rooms (project-scoped CRUD; FOR-QA-AUTO-05 setup/teardown) ----

    /**
     * Create a room via {@code POST /api/rooms} with a caller-supplied body (the
     * {@code RoomCreateRequest}-shaped {@link Map}/record; {@code projectId} and {@code roomTypeId}
     * are mandatory), returning the generated id. The generic CRUD create returns HTTP 200 with a
     * body carrying {@code id}. Requires a prior {@link #loginAdmin()}.
     *
     * @param body the room create request body
     * @return the created room id
     */
    public long createRoom(Object body) {
        APIResponse response = request.post("/api/rooms", withAuth().setData(body));
        JsonObject json = okJson(response, "POST /api/rooms");
        return getLong(json, "id");
    }

    /**
     * Delete a room via {@code DELETE /api/rooms/{id}}. Idempotent on {@code 404} so LIFO teardown
     * converges even when a project delete already cascaded the room away. Requires a prior
     * {@link #loginAdmin()}.
     *
     * @param id the room id to delete
     */
    public void deleteRoom(long id) {
        String path = "/api/rooms/" + id;
        APIResponse response = request.delete(path, withAuth());
        expectDeleted(response, "DELETE " + path);
    }

    /**
     * Return the id of the FIRST row on a paginated list endpoint (Spring {@code Page<{id,...}>}),
     * e.g. the first seeded room type via {@code /api/room-types}. Used to pick a MINIMAL valid
     * {@code roomTypeId} for a room without assuming a particular seed code exists. Requires a prior
     * {@link #loginAdmin()}.
     *
     * @param resourcePath the collection path, e.g. {@code /api/room-types}
     * @return the id of the first row
     * @throws IllegalStateException if the endpoint returns no rows
     */
    public long resolveFirstRowId(String resourcePath) {
        APIResponse response = request.get(resourcePath,
                withAuth().setQueryParam("page", "0").setQueryParam("size", "1"));
        JsonObject json = okJson(response, "GET " + resourcePath);
        if (!json.has("content") || !json.get("content").isJsonArray()
                || json.getAsJsonArray("content").isEmpty()) {
            throw new IllegalStateException(
                    "GET " + resourcePath + " returned no rows; expected at least one seeded row: "
                            + json);
        }
        JsonObject first = json.getAsJsonArray("content").get(0).getAsJsonObject();
        return getLong(first, "id");
    }

    // ---- Project members (FOR-QA-AUTO-05 setup) ----

    /**
     * Assign a user to a project under a project role via {@code POST /api/project-members} with the
     * body {@code {userId, projectId, projectRoleId}}. The endpoint returns HTTP 201 Created
     * ({@code AssignProjectMemberRequest} → {@code ProjectMemberResponse}); the membership is removed
     * for free when the project (and the client user) are torn down. Requires a prior
     * {@link #loginAdmin()}.
     *
     * @param userId        the user to assign
     * @param projectId     the target project
     * @param projectRoleId the project-role id the member is assigned under
     */
    public void assignProjectMember(long userId, long projectId, long projectRoleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("projectRoleId", projectRoleId);
        APIResponse response = request.post("/api/project-members", withAuth().setData(body));
        expectOk(response, "POST /api/project-members");
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        try {
            request.dispose();
        } finally {
            playwright.close();
        }
    }

    // ---- Internals ----

    /** Fresh {@link RequestOptions} carrying the Bearer token (login must have run first). */
    private RequestOptions withAuth() {
        if (accessToken == null) {
            throw new IllegalStateException(
                    "ApiHelper is not authenticated; call loginAdmin()/login(...) before this call.");
        }
        return RequestOptions.create().setHeader("Authorization", "Bearer " + accessToken);
    }

    /** Assert a 2xx response and parse its body as a JSON object. */
    private static JsonObject okJson(APIResponse response, String what) {
        expectOk(response, what);
        String text = response.text();
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** Assert a 2xx response, else throw with status + body for diagnosis. */
    private static void expectOk(APIResponse response, String what) {
        if (!response.ok()) {
            throw new IllegalStateException(
                    what + " failed: HTTP " + response.status() + " " + safeBody(response));
        }
    }

    /**
     * Accept a successful delete or an already-gone {@code 404} (idempotent teardown); throw on any
     * other non-2xx status.
     */
    private static void expectDeleted(APIResponse response, String what) {
        if (response.ok() || response.status() == 404) {
            return;
        }
        throw new IllegalStateException(
                what + " failed: HTTP " + response.status() + " " + safeBody(response));
    }

    private static String safeBody(APIResponse response) {
        try {
            return response.text();
        } catch (RuntimeException e) {
            return "<no body>";
        }
    }

    private static String getString(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : null;
    }

    private static long getLong(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new IllegalStateException(
                    "Expected numeric field '" + field + "' in response, got: " + json);
        }
        return json.get(field).getAsLong();
    }

    /**
     * A single role-permission grant: a resource id and the operation ids granted on it. Mirrors the
     * backend {@code PermissionEntryRequest{resourceId, operationIds}} contract.
     */
    public record PermissionEntry(long resourceId, List<Long> operationIds) {
    }
}
