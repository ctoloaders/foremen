package com.foremen.qa.support;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;

/**
 * Thin, per-acting-user HTTP client for the notification read API (FOR-QA-AUTO-05 offer-approval).
 *
 * <p>Unlike {@link ApiHelper} (which authenticates once as the seeded ADMIN and reuses that bearer
 * for setup/teardown), the notification endpoints are <b>acting-user-scoped</b>: {@code GET
 * /api/notifications} and {@code GET /api/notifications/unread-count} return only the rows whose
 * {@code recipient_id} is the caller. So every call here takes an explicit bearer token (the token
 * of the user whose inbox is being asserted — a MANAGER, or an OTP-signed-in CLIENT), rather than a
 * stored one. The helper owns its own Playwright + {@link APIRequestContext} bound to
 * {@link TestConfig#apiUrl()}, mirroring {@link ApiHelper#create()}'s setup, and is disposed via
 * {@link #close()} (registered for teardown by the step class).
 *
 * <p>Both reads fail fast on a non-2xx with the status + body so a broken assertion surfaces
 * immediately. JSON is parsed with Gson, matching the {@link ApiHelper} style; nothing is ever
 * string-interpolated into a request.
 */
public final class NotificationApiHelper implements AutoCloseable {

    private final Playwright playwright;
    private final APIRequestContext request;

    private NotificationApiHelper(Playwright playwright, APIRequestContext request) {
        this.playwright = playwright;
        this.request = request;
    }

    /**
     * Create a helper with its own Playwright + request context bound to the API base URL. The
     * caller owns the returned helper and must {@link #close()} it.
     */
    public static NotificationApiHelper create() {
        Playwright playwright = Playwright.create();
        APIRequestContext request = playwright.request().newContext(
                new APIRequest.NewContextOptions()
                        .setBaseURL(TestConfig.apiUrl()));
        return new NotificationApiHelper(playwright, request);
    }

    /**
     * List the {@code type} strings of the acting user's notifications (newest-first), via
     * {@code GET /api/notifications} authenticated as {@code bearerToken}. The endpoint returns a
     * JSON array of {@code {id, type, body, deepLink, read, createdDate}}; this extracts the
     * {@code type} field of each element in order.
     *
     * @param bearerToken the acting user's access token (no {@code Bearer } prefix)
     * @return the notification type strings, newest-first
     */
    public List<String> listTypes(String bearerToken) {
        APIResponse response = request.get("/api/notifications", auth(bearerToken));
        if (!response.ok()) {
            throw new IllegalStateException(
                    "GET /api/notifications failed: HTTP " + response.status() + " "
                            + safeBody(response));
        }
        JsonArray array = JsonParser.parseString(response.text()).getAsJsonArray();
        List<String> types = new ArrayList<>(array.size());
        for (var element : array) {
            JsonObject row = element.getAsJsonObject();
            if (row.has("type") && !row.get("type").isJsonNull()) {
                types.add(row.get("type").getAsString());
            }
        }
        return types;
    }

    /**
     * The acting user's unread notification count, via {@code GET /api/notifications/unread-count}
     * authenticated as {@code bearerToken}. The endpoint returns {@code {unreadCount}}.
     *
     * @param bearerToken the acting user's access token (no {@code Bearer } prefix)
     * @return the unread count
     */
    public long unreadCount(String bearerToken) {
        APIResponse response = request.get("/api/notifications/unread-count", auth(bearerToken));
        if (!response.ok()) {
            throw new IllegalStateException(
                    "GET /api/notifications/unread-count failed: HTTP " + response.status() + " "
                            + safeBody(response));
        }
        JsonObject json = JsonParser.parseString(response.text()).getAsJsonObject();
        if (!json.has("unreadCount") || json.get("unreadCount").isJsonNull()) {
            throw new IllegalStateException(
                    "GET /api/notifications/unread-count returned no unreadCount: " + json);
        }
        return json.get("unreadCount").getAsLong();
    }

    @Override
    public void close() {
        try {
            request.dispose();
        } finally {
            playwright.close();
        }
    }

    // ---- Internals ----

    private static RequestOptions auth(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalStateException(
                    "A non-blank bearer token is required to read acting-user-scoped notifications.");
        }
        return RequestOptions.create().setHeader("Authorization", "Bearer " + bearerToken);
    }

    private static String safeBody(APIResponse response) {
        try {
            return response.text();
        } catch (RuntimeException e) {
            return "<no body>";
        }
    }
}
