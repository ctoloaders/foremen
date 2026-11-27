package com.foremen.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * FOR-07-04a task 4.2 — a local fake Fintable API V2 for the integration tests. Serves, under
 * {@code /api/v2}: {@code POST /connections/link}, {@code GET /connections[/{id}]}, {@code GET /accounts},
 * {@code GET /accounts/{id}/transactions} (two pages: no cursor → two rows + {@code next_cursor};
 * {@code cursor=page2} → one row) and {@code GET /sync} (idle / finished states).
 *
 * <p>Every request is recorded as {@code METHOD path?decodedQuery} together with whether it carried a
 * bearer token. No real token or personal data is involved; the token the tests configure is a
 * made-up string.
 */
final class FakeFintableServer implements AutoCloseable {

    static final String BASE_PATH = "/api/v2";
    static final String CONNECTION_ID = "conn_enablebanking_fake01";
    static final String ACCOUNT_ID = "acc_fake_01";
    static final String IBAN = "PL61109010140000071219812874";

    static final String UPDATED_1 = "2026-11-18T10:00:00Z";
    static final String UPDATED_2 = "2026-11-18T11:00:00Z";
    static final String UPDATED_3 = "2026-11-18T12:00:00Z";

    private final HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Boolean> authorized = Collections.synchronizedList(new ArrayList<>());

    FakeFintableServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the fake Fintable server", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + BASE_PATH;
    }

    List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** Recorded requests whose line starts with {@code prefix} (e.g. {@code "GET /api/v2/accounts/"}). */
    List<String> requests(String prefix) {
        return requests().stream().filter(r -> r.startsWith(prefix)).toList();
    }

    /** {@code true} when every recorded authenticated request carried a bearer token. */
    boolean allAuthenticatedRequestsCarriedToken() {
        synchronized (authorized) {
            return !authorized.contains(Boolean.FALSE);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);
        requests.add(method + " " + path + (query == null ? "" : "?" + decode(query)));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorized.add(auth != null && auth.startsWith("Bearer ") && auth.length() > "Bearer ".length());
        ex.getRequestBody().readAllBytes();

        String rel = path.startsWith(BASE_PATH) ? path.substring(BASE_PATH.length()) : path;
        if (method.equals("POST") && rel.equals("/connections/link")) {
            respond(ex, 201, wrap("{\"url\":\"https://fintable.test/link/abc123\",\"expires_at\":\"2099-01-01T00:00:00Z\"}"));
        } else if (method.equals("GET") && rel.equals("/connections")) {
            respond(ex, 200, "{\"data\":[" + connectionJson() + "]}");
        } else if (method.equals("GET") && rel.equals("/connections/" + CONNECTION_ID)) {
            respond(ex, 200, wrap(connectionJson()));
        } else if (method.equals("GET") && rel.equals("/accounts")) {
            respond(ex, 200, "{\"data\":[" + accountJson() + "]}");
        } else if (method.equals("GET") && rel.equals("/accounts/" + ACCOUNT_ID + "/transactions")) {
            String cursor = params.get("cursor");
            if (cursor == null) {
                respond(ex, 200, "{\"data\":[" + tx("tx_fake_1", "-100.00", "2026-11-16", UPDATED_1, "Dostawca Jeden")
                        + "," + tx("tx_fake_2", "250.50", "2026-11-17", UPDATED_2, "Klient Dwa")
                        + "],\"next_cursor\":\"page2\"}");
            } else {
                respond(ex, 200, "{\"data\":[" + tx("tx_fake_3", "-40.25", "2026-11-18", UPDATED_3, "Dostawca Trzy")
                        + "],\"next_cursor\":null}");
            }
        } else if (method.equals("GET") && rel.equals("/sync")) {
            // Real shape: {"data":{"schedule":{...},"active_syncs":[],"connections":[{connection_id, sync_status}]}}
            respond(ex, 200, wrap("{\"schedule\":{\"type\":\"default\"},\"active_syncs\":[],\"connections\":[{"
                    + "\"connection_id\":\"" + CONNECTION_ID + "\",\"sync_status\":"
                    + "{\"state\":\"finished\",\"finished_at\":\"2026-11-18T09:00:00Z\"}}]}"));
        } else {
            respond(ex, 404, "{\"error\":{\"type\":\"not_found\",\"message\":\"no route " + method + " " + rel + "\"}}");
        }
    }

    /** Fintable wraps single objects: {"data": {...}, "workspace_id": ...}. */
    private static String wrap(String object) {
        return "{\"data\":" + object + ",\"workspace_id\":\"ws_fake\"}";
    }

    private static String connectionJson() {
        return "{\"id\":\"" + CONNECTION_ID + "\",\"institution_name\":\"Fake Bank PL\",\"healthy\":true,"
                + "\"needs_reconnect\":false,\"created_at\":\"2099-01-01T00:00:00Z\","
                + "\"last_successful_update\":\"2026-11-18T09:00:00Z\","
                + "\"sync_status\":{\"state\":\"succeeded\",\"finished_at\":\"2026-11-18T09:00:00Z\"}}";
    }

    private static String accountJson() {
        return "{\"id\":\"" + ACCOUNT_ID + "\",\"connection_id\":\"" + CONNECTION_ID + "\","
                + "\"name\":\"Konto firmowe\",\"display_name\":\"Konto firmowe\",\"type\":\"depository / checking\","
                + "\"currency\":\"PLN\",\"balance\":\"1000.00\",\"balance_available\":\"1000.00\","
                + "\"iban\":\"" + IBAN + "\",\"holder_name\":null,\"enabled\":true}";
    }

    private static String tx(String id, String amount, String date, String updatedAt, String counterparty) {
        boolean out = amount.startsWith("-");
        String side = out ? "creditor" : "debtor";
        return "{\"id\":\"" + id + "\",\"account_id\":\"" + ACCOUNT_ID + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"PLN\",\"date\":\"" + date + "\",\"description\":\"Platnosc " + id
                + "\",\"merchant\":null,\"account_owner\":null,\"category\":null,\"updated_at\":\"" + updatedAt
                + "\",\"raw\":{\"" + side + "\":{\"name\":\"" + counterparty + "\"}}}";
    }

    // ------------------------------------------------------------------ plumbing

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
