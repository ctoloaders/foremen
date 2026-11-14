package com.foremen.service.nip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.nip.NipLookupConfiguredCondition;
import com.foremen.config.nip.NipLookupProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.Optional;

/**
 * FOR-11-00 (Requirements 6.3, 6.4, 6.5) — HTTP-backed {@link NipLookupService}. Sends
 * {@code GET {baseUrl}?nip=<nip>} with the {@code X-Api-Key} header and maps the JSON answer
 * tolerantly (unknown properties ignored). A 404, an empty body or a record without a name yields
 * {@link Optional#empty()}; any transport or non-2xx failure raises {@link NipLookupUnavailableException}.
 *
 * <p>The API key is only ever placed in the request header; it is never logged and never part of an
 * exception message.
 */
@Slf4j
@Service
@Conditional(NipLookupConfiguredCondition.class)
public class RestNipLookupService implements NipLookupService {

    static final String API_KEY_HEADER = "X-Api-Key";

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    @Autowired
    public RestNipLookupService(NipLookupProperties properties) {
        this(properties, defaultBuilder(properties));
    }

    RestNipLookupService(NipLookupProperties properties, RestClient.Builder builder) {
        this.restClient = builder.build();
        this.baseUrl = properties.baseUrl();
        this.apiKey = properties.apiKey();
    }

    private static RestClient.Builder defaultBuilder(NipLookupProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder().requestFactory(factory);
    }

    @Override
    public Optional<NipLookupResult> lookup(String nip) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl).queryParam("nip", nip).build().toUri();
        String body;
        try {
            body = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            h.set(API_KEY_HEADER, apiKey);
                        }
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return null;
                        }
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                        throw new NipLookupUnavailableException("NIP registry answered with HTTP " + status);
                    });
        } catch (NipLookupUnavailableException e) {
            log.warn("NIP registry lookup failed: {}", e.getMessage());
            throw e;
        } catch (RestClientException | IllegalStateException e) {
            log.warn("NIP registry lookup failed: {}", e.getClass().getSimpleName());
            throw new NipLookupUnavailableException("NIP registry is unavailable", e);
        }
        return map(nip, body);
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    private Optional<NipLookupResult> map(String requestedNip, String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (java.io.IOException e) {
            throw new NipLookupUnavailableException("NIP registry returned an unreadable response", e);
        }
        if (root != null && root.isArray()) {
            root = root.isEmpty() ? null : root.get(0);
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String name = text(root, "name");
        if (name == null) {
            return Optional.empty();
        }
        String nip = text(root, "nip");
        String krs = text(root, "krs");
        return Optional.of(new NipLookupResult(
                nip != null ? nip : requestedNip,
                name,
                text(root, "address"),
                text(root, "regon"),
                krs,
                registry(text(root, "registry"), krs)));
    }

    private static NipLookupResult.Registry registry(String raw, String krs) {
        if (raw != null) {
            try {
                return NipLookupResult.Registry.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return NipLookupResult.Registry.UNKNOWN;
            }
        }
        return krs != null ? NipLookupResult.Registry.KRS : NipLookupResult.Registry.UNKNOWN;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
