package com.foremen.service.procurement.shipment.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * FOR-11-04 (R7.5): the only outbound HTTP component of tracking. HTTPS only, host pinned by the adapter
 * (a constant, never user input), connect and read timeouts, bounded body, redirects followed manually and
 * only within the same host, identifying {@code User-Agent}. Response bodies are never logged.
 */
@Component
public class TrackingHttpClient {

    public static final String USER_AGENT = "Foremen-Tracking/1.0 (+https://foremen.app)";
    static final int MAX_REDIRECTS = 3;

    private static final Logger log = LoggerFactory.getLogger(TrackingHttpClient.class);

    /** Successful (2xx) response; the body is bounded by {@code max-response-bytes}. */
    public record Response(int status, String body) {
    }

    private final HttpClient client;
    private final TrackingProperties properties;

    @Autowired
    public TrackingHttpClient(TrackingProperties properties) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), properties);
    }

    TrackingHttpClient(HttpClient client, TrackingProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** URL-encodes one path segment or query value. */
    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Builds {@code https://host + pathAndQuery}; {@code host} must be a bare host name (no scheme, port,
     * userinfo or path) and {@code pathAndQuery} must start with {@code /}.
     */
    static URI pinned(String host, String pathAndQuery) {
        if (host == null || !host.matches("[A-Za-z0-9.-]+") || pathAndQuery == null
                || !pathAndQuery.startsWith("/") || pathAndQuery.startsWith("//")) {
            throw new IllegalArgumentException("Invalid tracking endpoint");
        }
        return URI.create("https://" + host.toLowerCase(Locale.ROOT) + pathAndQuery);
    }

    /** GET {@code https://host/pathAndQuery}; classifies every failure as {@link TrackingFailure}. */
    public Response get(String host, String pathAndQuery, String accept) throws TrackingUnavailableException {
        URI uri;
        try {
            uri = pinned(host, pathAndQuery);
        } catch (IllegalArgumentException e) {
            throw new TrackingUnavailableException(TrackingFailure.OTHER, "Invalid tracking endpoint");
        }
        String pinnedHost = uri.getHost();
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(properties.readTimeout())
                        .header("User-Agent", USER_AGENT)
                        .GET();
                if (accept != null) {
                    request.header("Accept", accept);
                }
                HttpResponse<InputStream> response =
                        client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                try (InputStream in = response.body()) {
                    if (status >= 300 && status < 400) {
                        uri = nextHop(uri, response.headers().firstValue("Location").orElse(null), pinnedHost);
                        continue;
                    }
                    if (status >= 200 && status < 300) {
                        return new Response(status, readBounded(in));
                    }
                    throw classify(status);
                }
            }
            throw new TrackingUnavailableException(TrackingFailure.OTHER, "Too many redirects");
        } catch (HttpTimeoutException e) {
            throw new TrackingUnavailableException(TrackingFailure.TIMEOUT, "Carrier request timed out");
        } catch (IOException e) {
            log.debug("Tracking I/O failure: {}", e.getClass().getSimpleName());
            throw new TrackingUnavailableException(TrackingFailure.OTHER, "Carrier request failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackingUnavailableException(TrackingFailure.OTHER, "Carrier request interrupted");
        }
    }

    private static URI nextHop(URI current, String location, String pinnedHost) throws TrackingUnavailableException {
        if (location == null || location.isBlank()) {
            throw new TrackingUnavailableException(TrackingFailure.OTHER, "Redirect without location");
        }
        URI target;
        try {
            target = current.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            throw new TrackingUnavailableException(TrackingFailure.OTHER, "Invalid redirect");
        }
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null
                || target.getHost() == null || !pinnedHost.equalsIgnoreCase(target.getHost())
                || (target.getPort() != -1 && target.getPort() != 443)) {
            throw new TrackingUnavailableException(TrackingFailure.BLOCKED, "Redirect to another host refused");
        }
        return target;
    }

    private static TrackingUnavailableException classify(int status) {
        return switch (status) {
            case 404, 410 -> new TrackingUnavailableException(TrackingFailure.NOT_FOUND, "Carrier HTTP " + status);
            case 429 -> new TrackingUnavailableException(TrackingFailure.RATE_LIMITED, "Carrier HTTP 429");
            case 401, 403 -> new TrackingUnavailableException(TrackingFailure.BLOCKED, "Carrier HTTP " + status);
            case 408, 504 -> new TrackingUnavailableException(TrackingFailure.TIMEOUT, "Carrier HTTP " + status);
            default -> new TrackingUnavailableException(TrackingFailure.OTHER, "Carrier HTTP " + status);
        };
    }

    private String readBounded(InputStream in) throws IOException, TrackingUnavailableException {
        long max = properties.maxResponseBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max) {
                throw new TrackingUnavailableException(TrackingFailure.PARSE_ERROR, "Carrier response too large");
            }
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
