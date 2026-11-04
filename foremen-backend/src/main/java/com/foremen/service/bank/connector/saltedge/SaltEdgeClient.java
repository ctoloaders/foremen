package com.foremen.service.bank.connector.saltedge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.bank.connector.ConnectorError;
import com.foremen.service.bank.connector.ConnectorErrorClass;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * FOR-07-04 (Requirements 8.4, 8.5, 8.6, 12.1, 12.2; design §SaltEdgeClient): the low-level HTTP
 * client for both Salt Edge modes (Partners API v1 and API v6), shared by {@code PartnerModeApi} and
 * {@code ClientModeApi}.
 *
 * <p>Every call goes through {@link #exchange(HttpMethod, String, Object)}:
 * <ol>
 *   <li>the body is serialized <b>once</b> to a JSON string — the same bytes are signed and sent;</li>
 *   <li>headers {@code Accept}, {@code Content-type}, {@code App-id}, {@code Secret};</li>
 *   <li>when a private key is configured, {@code Expires-at = now + 60 s} and
 *       {@code Signature = SaltEdgeRequestSigner.sign(expiresAt, METHOD, fullUrl, body)};</li>
 *   <li>a non-2xx body {@code {error:{class,message,request_id}}} → {@link SaltEdgeErrorMapper};</li>
 *   <li>{@code TRANSIENT} errors are retried 3× with backoff 1 s / 4 s / 16 s (injectable); other
 *       classes are thrown immediately;</li>
 *   <li>DEBUG logging with redacted {@code App-id} / {@code Secret} / {@code Signature}; bodies are
 *       never logged.</li>
 * </ol>
 * Paginated reads follow {@code meta.next_id} via {@code from_id} ({@link #getPage}, {@link #getAll}).
 */
public class SaltEdgeClient {

    private static final Logger log = LoggerFactory.getLogger(SaltEdgeClient.class);

    public static final List<Duration> DEFAULT_RETRY_DELAYS =
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofSeconds(16));
    static final long SIGNATURE_TTL_SECONDS = 60;
    static final int MAX_PAGES = 10_000;

    static final String HEADER_APP_ID = "App-id";
    static final String HEADER_SECRET = "Secret";
    static final String HEADER_EXPIRES_AT = "Expires-at";
    static final String HEADER_SIGNATURE = "Signature";
    private static final Set<String> REDACTED_HEADERS = Set.of(
            HEADER_APP_ID.toLowerCase(Locale.ROOT), HEADER_SECRET.toLowerCase(Locale.ROOT),
            HEADER_SIGNATURE.toLowerCase(Locale.ROOT), HttpHeaders.AUTHORIZATION.toLowerCase(Locale.ROOT));

    /** Pause between retries; injectable so tests do not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** One page of a list endpoint: the {@code data} items and {@code meta.next_id} ({@code null} on the last page). */
    public record Page(List<JsonNode> data, String nextId) {
        public Page {
            data = List.copyOf(data);
        }
    }

    private record RawResponse(int status, String body, String requestId) {
    }

    private final SaltEdgeProperties properties;
    private final RestClient restClient;
    private final String baseUrl;
    private final SaltEdgeRequestSigner signer;
    private final String signerProblem;
    private final Clock clock;
    private final Sleeper sleeper;
    private final List<Duration> retryDelays;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param restClientBuilder builder for the underlying client (tests bind a {@code MockRestServiceServer})
     * @param signer            request signer, or {@code null} when signing is off
     * @param signerProblem     non-null when a private key is configured but could not be loaded —
     *                          every request then fails with CONFIG instead of going out unsigned
     */
    public SaltEdgeClient(SaltEdgeProperties properties, RestClient.Builder restClientBuilder,
                          SaltEdgeRequestSigner signer, String signerProblem, Clock clock, Sleeper sleeper,
                          List<Duration> retryDelays) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
        this.baseUrl = properties.effectiveBaseUrl();
        this.signer = signer;
        this.signerProblem = signerProblem;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.retryDelays = List.copyOf(retryDelays);
    }

    /**
     * Production factory: JDK HTTP client with {@code http-timeout} for connect and read, default
     * retry delays, and the signer loaded from {@code private-key-path} when configured.
     */
    public static SaltEdgeClient create(SaltEdgeProperties properties, Clock clock) {
        Duration timeout = properties.httpTimeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        SaltEdgeRequestSigner signer = null;
        String signerProblem = null;
        if (properties.signingEnabled()) {
            try {
                signer = SaltEdgeRequestSigner.fromPem(Files.readString(Path.of(properties.privateKeyPath())));
            } catch (IOException | RuntimeException e) {
                // Never log key material — only the reason class.
                signerProblem = "foremen.bank-feed.saltedge.private-key-path cannot be loaded ("
                        + e.getClass().getSimpleName() + ")";
                log.error("Salt Edge request signing misconfigured: {}", signerProblem);
            }
        }
        return new SaltEdgeClient(properties, RestClient.builder().requestFactory(factory), signer, signerProblem,
                clock, Thread::sleep, DEFAULT_RETRY_DELAYS);
    }

    public SaltEdgeProperties properties() {
        return properties;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean signingEnabled() {
        return signer != null;
    }

    public JsonNode get(String pathAndQuery) {
        return exchange(HttpMethod.GET, pathAndQuery, null);
    }

    public JsonNode post(String pathAndQuery, Object body) {
        return exchange(HttpMethod.POST, pathAndQuery, body);
    }

    public JsonNode put(String pathAndQuery, Object body) {
        return exchange(HttpMethod.PUT, pathAndQuery, body);
    }

    public JsonNode delete(String pathAndQuery) {
        return exchange(HttpMethod.DELETE, pathAndQuery, null);
    }

    /**
     * Sends one logical request (with TRANSIENT retries) and returns the parsed response root.
     *
     * @param pathAndQuery path relative to the mode base URL, e.g. {@code /connections?customer_id=1}
     * @param body         {@code null}, a JSON {@code String} sent as is, or an object serialized with Jackson
     * @throws ConnectorError mapped per {@link SaltEdgeErrorMapper}
     */
    public JsonNode exchange(HttpMethod method, String pathAndQuery, Object body) {
        Objects.requireNonNull(method, "method");
        assertConfigured();
        String fullUrl = fullUrl(pathAndQuery);
        String jsonBody = serialize(body);
        int maxAttempts = retryDelays.size() + 1;
        for (int attempt = 1; ; attempt++) {
            try {
                return once(method, fullUrl, jsonBody, attempt);
            } catch (ConnectorError e) {
                if (!e.isRetryable() || attempt >= maxAttempts) {
                    throw e;
                }
                Duration delay = retryDelays.get(attempt - 1);
                log.debug("Salt Edge {} {} transient failure (attempt {}/{}), retrying in {}",
                        method, pathOnly(fullUrl), attempt, maxAttempts, delay);
                pause(delay, e);
            }
        }
    }

    /** One page of a list endpoint; {@code fromId} ({@code null} for the first page) is sent as {@code from_id}. */
    public Page getPage(String path, Map<String, String> query, String fromId) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null) {
            params.putAll(query);
        }
        if (fromId != null && !fromId.isBlank()) {
            params.put("from_id", fromId);
        }
        JsonNode root = get(pathWithQuery(path, params));
        List<JsonNode> items = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            data.forEach(items::add);
        }
        JsonNode next = root.path("meta").path("next_id");
        String nextId = next.isMissingNode() || next.isNull() || next.asText().isBlank() ? null : next.asText();
        return new Page(items, nextId);
    }

    /** All items of a list endpoint, looping on {@code meta.next_id} until it is null. */
    public List<JsonNode> getAll(String path, Map<String, String> query) {
        List<JsonNode> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String fromId = null;
        for (int pages = 0; pages < MAX_PAGES; pages++) {
            Page page = getPage(path, query, fromId);
            all.addAll(page.data());
            if (page.nextId() == null) {
                return all;
            }
            if (!seen.add(page.nextId())) {
                log.warn("Salt Edge pagination of {} repeated next_id; stopping", path);
                return all;
            }
            fromId = page.nextId();
        }
        log.warn("Salt Edge pagination of {} exceeded {} pages; stopping", path, MAX_PAGES);
        return all;
    }

    /** {@code path?k=v&...} with URL-encoded values, in the map's iteration order; null values are skipped. */
    public static String pathWithQuery(String path, Map<String, String> query) {
        Objects.requireNonNull(path, "path");
        if (query == null || query.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path);
        char separator = path.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            sb.append(separator)
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return sb.toString();
    }

    /** The absolute URL exactly as sent and signed. */
    public String fullUrl(String pathAndQuery) {
        Objects.requireNonNull(pathAndQuery, "pathAndQuery");
        if (pathAndQuery.startsWith("https://") || pathAndQuery.startsWith("http://")) {
            return pathAndQuery;
        }
        return baseUrl + (pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery);
    }

    /** Header values for logging: {@code App-id}, {@code Secret}, {@code Signature} redacted (R8.6). */
    static Map<String, String> redactHeaders(HttpHeaders headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((name, values) -> out.put(name,
                REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT)) ? "***" : String.join(",", values)));
        return out;
    }

    private JsonNode once(HttpMethod method, String fullUrl, String jsonBody, int attempt) {
        HttpHeaders headers = buildHeaders(method, fullUrl, jsonBody);
        long started = System.nanoTime();
        if (log.isDebugEnabled()) {
            log.debug("Salt Edge -> {} {} attempt={} headers={}", method, pathOnly(fullUrl), attempt,
                    redactHeaders(headers));
        }
        RawResponse response;
        try {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(URI.create(fullUrl))
                    .headers(h -> h.addAll(headers));
            if (jsonBody != null) {
                spec.body(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            response = spec.exchange((request, resp) -> new RawResponse(
                    resp.getStatusCode().value(),
                    new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8),
                    resp.getHeaders().getFirst("X-Request-Id")));
        } catch (RestClientException e) {
            throw SaltEdgeErrorMapper.transientFailure(
                    "I/O error on " + method + " " + pathOnly(fullUrl) + " (" + rootCauseName(e) + ")", e);
        }
        if (log.isDebugEnabled()) {
            log.debug("Salt Edge <- {} {} status={} requestId={} in {} ms", method, pathOnly(fullUrl),
                    response.status(), response.requestId(), (System.nanoTime() - started) / 1_000_000);
        }
        if (response.status() < 200 || response.status() >= 300) {
            ConnectorError error = SaltEdgeErrorMapper.fromResponse(response.status(), response.body());
            log.debug("Salt Edge {} {} failed: class={} errorClass={} requestId={}", method, pathOnly(fullUrl),
                    error.getProviderErrorCode(), error.getErrorClass(), error.getRequestId());
            throw error;
        }
        return parse(response, method, fullUrl);
    }

    private HttpHeaders buildHeaders(HttpMethod method, String fullUrl, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HEADER_APP_ID, properties.appId());
        headers.set(HEADER_SECRET, properties.secret());
        if (signer != null) {
            long expiresAt = clock.instant().getEpochSecond() + SIGNATURE_TTL_SECONDS;
            headers.set(HEADER_EXPIRES_AT, Long.toString(expiresAt));
            headers.set(HEADER_SIGNATURE, signer.sign(expiresAt, method.name(), fullUrl, jsonBody));
        }
        return headers;
    }

    private void assertConfigured() {
        if (properties.appId() == null || properties.secret() == null) {
            throw new ConnectorError(ConnectorErrorClass.CONFIG, null, SaltEdgeErrorMapper.KEY_CONFIG,
                    "Salt Edge app-id / secret are not configured", null, null);
        }
        if (signerProblem != null) {
            throw new ConnectorError(ConnectorErrorClass.CONFIG, null, SaltEdgeErrorMapper.KEY_CONFIG,
                    signerProblem, null, null);
        }
    }

    private String serialize(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Salt Edge request body is not serializable", e);
        }
    }

    private void pause(Duration delay, ConnectorError lastError) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw lastError;
        }
    }

    private JsonNode parse(RawResponse response, HttpMethod method, String fullUrl) {
        if (response.body() == null || response.body().isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw SaltEdgeErrorMapper.transientFailure(
                    "unparsable response on " + method + " " + pathOnly(fullUrl), e);
        }
    }

    private static String pathOnly(String fullUrl) {
        int q = fullUrl.indexOf('?');
        return q < 0 ? fullUrl : fullUrl.substring(0, q);
    }

    private static String rootCauseName(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
