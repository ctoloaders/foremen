package com.foremen.service.bank.connector.fintable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import com.foremen.service.bank.connector.fintable.dto.FintableTransactionsPage;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * FOR-07-04a (Requirements 3.1, 3.3-3.5, 14.6; design §FintableClient): thin typed wrapper over
 * {@link RestClient} for the allowed Fintable endpoints only.
 *
 * <ul>
 *   <li>{@code Authorization: Bearer <token>} is set in exactly one place ({@link #authorize}); the
 *       token is read from a supplier per request and is never logged or put in a message;</li>
 *   <li>{@code workspace_id} is added as a query parameter to authenticated calls when configured;</li>
 *   <li>public {@link #institutions} sends neither {@code Authorization} nor {@code workspace_id};</li>
 *   <li>non-2xx responses become {@link FintableConnectorError} through {@link FintableErrorMapper};
 *       there is no retry here (a {@code 429} is never retried, TRANSIENT retry is the orchestrator's);</li>
 *   <li>logging is limited to method, path (no query) and status.</li>
 * </ul>
 */
public class FintableClient {

    private static final Logger log = LoggerFactory.getLogger(FintableClient.class);

    static final int TRANSACTIONS_LIMIT = 500;

    private record RawResponse(int status, String body, String retryAfter) {
    }

    private final RestClient restClient;
    private final String baseUrl;
    private final String workspaceId;
    private final Supplier<String> tokenSupplier;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param restClientBuilder builder for the underlying client (tests bind a {@code MockRestServiceServer})
     * @param tokenSupplier     supplies the personal access token for each authenticated request
     */
    public FintableClient(FintableProperties properties, RestClient.Builder restClientBuilder,
                          Supplier<String> tokenSupplier) {
        Objects.requireNonNull(properties, "properties");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
        this.baseUrl = properties.effectiveBaseUrl();
        this.workspaceId = properties.workspaceId();
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    /** Production factory: JDK HTTP client with {@code http-timeout} for connect and read. */
    public static FintableClient create(FintableProperties properties, Supplier<String> tokenSupplier) {
        Duration timeout = properties.httpTimeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return new FintableClient(properties, RestClient.builder().requestFactory(factory), tokenSupplier);
    }

    // ---- authenticated endpoints -------------------------------------------------------------

    /** {@code GET /me}: availability probe, plan, {@code can_sync}. */
    public JsonNode me() {
        return object(send(HttpMethod.GET, "/me", Map.of(), null, true));
    }

    /** {@code GET /connections}. */
    public JsonNode connections() {
        return send(HttpMethod.GET, "/connections", Map.of(), null, true);
    }

    /** {@code GET /connections/{id}}. */
    public JsonNode connection(String connectionId) {
        return object(send(HttpMethod.GET, "/connections/" + segment(connectionId), Map.of(), null, true));
    }

    /** {@code POST /connections/link} with {@code {institution, redirect_url?}}; response has {@code url}, {@code expires_at}. */
    public JsonNode mintLink(String slug, String redirectUrl) {
        ObjectNode body = json.createObjectNode().put("institution", Objects.requireNonNull(slug, "slug"));
        if (redirectUrl != null && !redirectUrl.isBlank()) {
            body.put("redirect_url", redirectUrl);
        }
        return object(send(HttpMethod.POST, "/connections/link", Map.of(), body, true));
    }

    /** {@code POST /connections/{id}/link} with {@code {redirect_url?}}. */
    public JsonNode mintReconnectLink(String connectionId, String redirectUrl) {
        ObjectNode body = json.createObjectNode();
        if (redirectUrl != null && !redirectUrl.isBlank()) {
            body.put("redirect_url", redirectUrl);
        }
        return object(send(HttpMethod.POST, "/connections/" + segment(connectionId) + "/link", Map.of(), body, true));
    }

    /** {@code DELETE /connections/{id}}. */
    public JsonNode deleteConnection(String connectionId) {
        return object(send(HttpMethod.DELETE, "/connections/" + segment(connectionId), Map.of(), null, true));
    }

    /** {@code GET /accounts?connection_id=}; the {@code data} array (or the root when it is an array). */
    public List<JsonNode> accounts(String connectionId) {
        JsonNode root = send(HttpMethod.GET, "/accounts", Map.of("connection_id", connectionId), null, true);
        return items(root);
    }

    /**
     * {@code GET /accounts/{id}/transactions?order=updated&limit=500&include=raw[&updated_since=][&cursor=]}.
     *
     * @param since  optional lower bound for {@code updated_at}, sent as ISO-8601 UTC
     * @param cursor optional Fintable {@code next_cursor}
     */
    public FintableTransactionsPage transactions(String accountId, Instant since, String cursor) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("order", "updated");
        query.put("limit", Integer.toString(TRANSACTIONS_LIMIT));
        query.put("include", "raw");
        if (since != null) {
            query.put("updated_since", since.toString());
        }
        if (cursor != null && !cursor.isBlank()) {
            query.put("cursor", cursor);
        }
        JsonNode root = send(HttpMethod.GET, "/accounts/" + segment(accountId) + "/transactions", query, null, true);
        JsonNode next = root.path("next_cursor");
        String nextCursor = next.isMissingNode() || next.isNull() ? null : next.asText();
        return new FintableTransactionsPage(items(root), nextCursor);
    }

    /** {@code GET /sync}: the sync schedule and the per-connection sync status (read-only). */
    public JsonNode sync() {
        return object(send(HttpMethod.GET, "/sync", Map.of(), null, true));
    }

    /** {@code POST /sync/{id}}: starts a sync of one connection ({@code started} / {@code already_syncing}). */
    public JsonNode syncOne(String connectionId) {
        return object(send(HttpMethod.POST, "/sync/" + segment(connectionId), Map.of(), null, true));
    }

    // ---- public endpoint ---------------------------------------------------------------------

    /** Public {@code GET /institutions?q=&country=&supported=1}: no {@code Authorization}, no {@code workspace_id}. */
    public JsonNode institutions(String q, String country) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("q", q);
        query.put("country", country);
        query.put("supported", "1");
        return send(HttpMethod.GET, "/institutions", query, null, false);
    }

    // ---- internals ---------------------------------------------------------------------------

    private JsonNode send(HttpMethod method, String path, Map<String, String> query, Object body,
                          boolean authenticated) {
        Map<String, String> params = new LinkedHashMap<>(query);
        String token = null;
        if (authenticated) {
            token = tokenSupplier.get();
            if (token == null || token.isBlank()) {
                throw new FintableConnectorError(ConnectorErrorClass.CONFIG, null,
                        FintableErrorMapper.KEY_TOKEN_INVALID, "Fintable API token is not configured", null, null,
                        null);
            }
            if (workspaceId != null) {
                params.put("workspace_id", workspaceId);
            }
        }
        URI uri = URI.create(baseUrl + path + queryString(params));
        String jsonBody = serialize(body);
        RawResponse response;
        try {
            final String bearer = token;
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(uri)
                    .headers(h -> {
                        h.setAccept(List.of(MediaType.APPLICATION_JSON));
                        if (bearer != null) {
                            authorize(h, bearer);
                        }
                    });
            if (jsonBody != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            response = spec.exchange((request, resp) -> new RawResponse(
                    resp.getStatusCode().value(),
                    new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8),
                    resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
        } catch (RestClientException e) {
            log.debug("Fintable {} {} failed: {}", method, path, rootCauseName(e));
            throw FintableErrorMapper.transientFailure(
                    "I/O error on " + method + " " + path + " (" + rootCauseName(e) + ")", e);
        }
        log.debug("Fintable {} {} -> {}", method, path, response.status());
        if (response.status() < 200 || response.status() >= 300) {
            throw FintableErrorMapper.fromResponse(response.status(), method.name(), path, response.body(),
                    response.retryAfter(), token);
        }
        return parse(response, method, path);
    }

    /** The single place where the {@code Authorization} header is written. */
    private static void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
    }

    private JsonNode parse(RawResponse response, HttpMethod method, String path) {
        if (response.body() == null || response.body().isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw FintableErrorMapper.transientFailure("unparsable response on " + method + " " + path, e);
        }
    }

    private String serialize(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Fintable request body is not serializable", e);
        }
    }

    /**
     * Fintable wraps single objects as {@code {"data": {...}, "workspace_id": ...}}; returns the inner
     * object, or the root itself when it is not wrapped (BUG-2).
     */
    static JsonNode object(JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        return data != null && data.isObject() ? data : root;
    }

    private static List<JsonNode> items(JsonNode root) {
        JsonNode data = root.isArray() ? root : root.path("data");
        List<JsonNode> out = new ArrayList<>();
        if (data.isArray()) {
            data.forEach(out::add);
        }
        return out;
    }

    private static String queryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        char separator = '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            sb.append(separator)
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return sb.toString();
    }

    private static String segment(String id) {
        Objects.requireNonNull(id, "id");
        return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
