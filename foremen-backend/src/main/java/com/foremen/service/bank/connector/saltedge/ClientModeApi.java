package com.foremen.service.bank.connector.saltedge;

import static com.foremen.service.bank.connector.saltedge.SaltEdgeModeApi.alreadyGone;
import static com.foremen.service.bank.connector.saltedge.SaltEdgeModeApi.dataObject;
import static com.foremen.service.bank.connector.saltedge.SaltEdgeModeApi.instant;
import static com.foremen.service.bank.connector.saltedge.SaltEdgeModeApi.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.connector.ConnectorError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * FOR-07-04 (Requirements 8.7, 9.1, 9.4, 9.5, 9.7, 9.8; design §Salt Edge connector): the CLIENT mode —
 * Salt Edge API v6 ({@code https://www.saltedge.com/api/v6}), own licence / own bank keys.
 *
 * <p>Routes pinned against <a href="https://docs.saltedge.com/v6/api_reference">the v6 reference</a>
 * (fixture {@code saltedge/client/routes.json}):
 * <ul>
 *   <li>identity — {@code GET /customers} (paged, match {@code identifier}); {@code POST /customers {identifier}}</li>
 *   <li>session — {@code POST /connections/connect}; {@code POST /connections/{id}/reconnect};
 *       {@code POST /connections/{id}/refresh}; {@code POST /connections/{id}/background_refresh} → {@code data.connect_url}</li>
 *   <li>connection — {@code GET /connections/{id}} (incl. {@code holder_info}, {@code last_consent_id})</li>
 *   <li>consent — {@code GET /consents/{id}?connection_id=}; fallback {@code GET /consents?connection_id=} (last)</li>
 *   <li>data — {@code GET /accounts?connection_id=}; {@code GET /transactions?connection_id&account_id&from_id};
 *       pending {@code GET /transactions?connection_id&account_id&pending=true}</li>
 *   <li>providers — {@code GET /providers?country_code&include_sandboxes&exclude_inactive=true&include_ais_fields=true}
 *       (+ {@code key_owner=saltedge})</li>
 *   <li>disable — {@code PUT /consents/{id}/revoke?connection_id=}; {@code DELETE /connections/{id}}</li>
 * </ul>
 */
public class ClientModeApi implements SaltEdgeModeApi {

    static final String DUPLICATED_CUSTOMER = "DuplicatedCustomer";
    static final String CATEGORIZATION = "business";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SaltEdgeClient client;

    public ClientModeApi(SaltEdgeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public SaltEdgeMode mode() {
        return SaltEdgeMode.CLIENT;
    }

    // ---------------------------------------------------------------- identity

    /**
     * Looks the customer up by {@code identifier} first (so the common "already exists" case costs no
     * failed request), creates it otherwise; a {@code DuplicatedCustomer} race is resolved by a second lookup.
     */
    @Override
    public String ensureIdentity() {
        String identifier = client.properties().customerIdentifier();
        if (identifier == null) {
            throw SaltEdgeModeApi.missingIdentity("customer-identifier");
        }
        Optional<String> existing = findCustomer(identifier);
        if (existing.isPresent()) {
            return existing.get();
        }
        ObjectNode body = NODES.objectNode();
        body.putObject("data").put("identifier", identifier);
        try {
            String id = text(dataObject(client.post("/customers", body), "POST /customers"), "customer_id");
            if (id == null) {
                throw SaltEdgeErrorMapper.transientFailure("POST /customers returned no customer_id", null);
            }
            return id;
        } catch (ConnectorError e) {
            if (DUPLICATED_CUSTOMER.equals(e.getProviderErrorCode())) {
                return findCustomer(identifier).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    Optional<String> findCustomer(String identifier) {
        for (JsonNode customer : client.getAll("/customers", Map.of())) {
            if (identifier.equals(text(customer, "identifier"))) {
                return Optional.ofNullable(text(customer, "customer_id"));
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- sessions

    @Override
    public SessionUrl createSession(SessionRequest request) {
        ObjectNode data = NODES.objectNode();
        data.put("customer_id", request.customerId());
        data.set("consent", consent(request));
        data.set("attempt", attempt(request, true));
        data.putObject("widget").put("skip_provider_selection", request.providerCode() != null);
        ObjectNode provider = data.putObject("provider");
        if (request.providerCode() != null) {
            provider.put("code", request.providerCode());
        }
        provider.put("include_sandboxes", request.includeFakeProviders());
        flags(data);
        data.put("categorization", CATEGORIZATION);
        return session(client.post("/connections/connect", wrap(data)), "POST /connections/connect");
    }

    @Override
    public SessionUrl reconnectSession(String connectionId, SessionRequest request) {
        ObjectNode data = NODES.objectNode();
        data.set("consent", consent(request));
        data.set("attempt", attempt(request, true));
        flags(data);
        String path = "/connections/" + id(connectionId) + "/reconnect";
        return session(client.post(path, wrap(data)), "POST " + path);
    }

    @Override
    public SessionUrl refreshSession(String connectionId, SessionRequest request) {
        ObjectNode data = NODES.objectNode();
        data.set("attempt", attempt(request, true));
        flags(data);
        String path = "/connections/" + id(connectionId) + "/refresh";
        return session(client.post(path, wrap(data)), "POST " + path);
    }

    @Override
    public boolean supportsBackgroundRefresh() {
        return true;
    }

    @Override
    public JsonNode backgroundRefresh(String connectionId, SessionRequest request) {
        ObjectNode data = NODES.objectNode();
        data.set("attempt", attempt(request, false));
        String path = "/connections/" + id(connectionId) + "/background_refresh";
        return dataObject(client.post(path, wrap(data)), "POST " + path);
    }

    /** {@code consent{scopes: [accounts, transactions (, holder_info)], from_date}}. */
    static ObjectNode consent(SessionRequest request) {
        ObjectNode consent = NODES.objectNode();
        ArrayNode scopes = consent.putArray("scopes").add("accounts").add("transactions");
        if (request.holderInfo()) {
            scopes.add("holder_info");
        }
        if (request.fromDate() != null) {
            consent.put("from_date", request.fromDate().toString());
        }
        return consent;
    }

    /** {@code attempt{fetch_scopes: [accounts, balance, transactions (, holder_info)], fetch_from_date, return_to, custom_fields}}. */
    static ObjectNode attempt(SessionRequest request, boolean withReturnTo) {
        ObjectNode attempt = NODES.objectNode();
        ArrayNode scopes = attempt.putArray("fetch_scopes").add("accounts").add("balance").add("transactions");
        if (request.holderInfo()) {
            scopes.add("holder_info");
        }
        if (request.fromDate() != null) {
            attempt.put("fetch_from_date", request.fromDate().toString());
        }
        if (withReturnTo && request.returnTo() != null) {
            attempt.put("return_to", request.returnTo());
        }
        ObjectNode custom = attempt.putObject("custom_fields");
        request.customFields().forEach(custom::put);
        return attempt;
    }

    private static void flags(ObjectNode data) {
        data.put("return_connection_id", true);
        data.put("return_error_class", true);
        data.put("automatic_refresh", true);
    }

    private static SessionUrl session(JsonNode root, String what) {
        JsonNode data = dataObject(root, what);
        String url = text(data, "connect_url");
        if (url == null) {
            throw SaltEdgeErrorMapper.transientFailure(what + " returned no connect_url", null);
        }
        return new SessionUrl(url, instant(data, "expires_at"));
    }

    // ---------------------------------------------------------------- connection + consent

    @Override
    public JsonNode connection(String connectionId) {
        String path = "/connections/" + id(connectionId);
        return dataObject(client.get(path), "GET " + path);
    }

    @Override
    public ConsentSnapshot consents(JsonNode connection) {
        String connectionId = id(text(connection, "id"));
        return new ConsentSnapshot(latestConsent(client, connectionId, text(connection, "last_consent_id")), null);
    }

    /** {@code GET /consents/{last_consent_id}?connection_id=}, else the last of {@code GET /consents?connection_id=}. */
    static JsonNode latestConsent(SaltEdgeClient client, String connectionId, String lastConsentId) {
        if (lastConsentId != null) {
            String path = SaltEdgeClient.pathWithQuery("/consents/" + lastConsentId, Map.of("connection_id", connectionId));
            return dataObject(client.get(path), "GET /consents/{id}");
        }
        List<JsonNode> all = client.getAll("/consents", Map.of("connection_id", connectionId));
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    @Override
    public Optional<JsonNode> holderInfo(JsonNode connection) {
        JsonNode holder = connection == null ? null : connection.get("holder_info");
        return holder == null || !holder.isObject() || holder.isEmpty() ? Optional.empty() : Optional.of(holder);
    }

    // ---------------------------------------------------------------- data reads

    @Override
    public List<JsonNode> accounts(String connectionId) {
        return client.getAll("/accounts", Map.of("connection_id", id(connectionId)));
    }

    @Override
    public SaltEdgeClient.Page transactions(String connectionId, String accountId, String fromId) {
        return client.getPage("/transactions", accountQuery(connectionId, accountId), fromId);
    }

    @Override
    public List<JsonNode> pendingTransactions(String connectionId, String accountId) {
        Map<String, String> query = accountQuery(connectionId, accountId);
        query.put("pending", "true");
        return client.getAll("/transactions", query);
    }

    static Map<String, String> accountQuery(String connectionId, String accountId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("connection_id", id(connectionId));
        if (accountId != null) {
            query.put("account_id", accountId);
        }
        return query;
    }

    @Override
    public List<JsonNode> providers(String countryCode, boolean includeFake) {
        return client.getAll("/providers", providerQuery(countryCode, includeFake));
    }

    @Override
    public Optional<List<JsonNode>> providersWithSaltEdgeKeys(String countryCode, boolean includeFake) {
        Map<String, String> query = providerQuery(countryCode, includeFake);
        query.put("key_owner", "saltedge");
        return Optional.of(client.getAll("/providers", query));
    }

    private static Map<String, String> providerQuery(String countryCode, boolean includeFake) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("country_code", Objects.requireNonNull(countryCode, "countryCode"));
        query.put("include_sandboxes", Boolean.toString(includeFake));
        query.put("exclude_inactive", "true");
        query.put("include_ais_fields", "true");
        return query;
    }

    // ---------------------------------------------------------------- revoke / delete

    @Override
    public void revokeAndRemove(String connectionId) {
        String id = id(connectionId);
        JsonNode connection;
        try {
            connection = connection(id);
        } catch (ConnectorError e) {
            if (alreadyGone(e)) {
                return;
            }
            throw e;
        }
        String consentId = text(connection, "last_consent_id");
        if (consentId != null) {
            String path = SaltEdgeClient.pathWithQuery("/consents/" + consentId + "/revoke", Map.of("connection_id", id));
            try {
                client.put(path, null);
            } catch (ConnectorError e) {
                if (!alreadyGone(e)) {
                    throw e;
                }
            }
        }
        try {
            client.delete("/connections/" + id);
        } catch (ConnectorError e) {
            if (!alreadyGone(e)) {
                throw e;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static ObjectNode wrap(ObjectNode data) {
        ObjectNode root = NODES.objectNode();
        root.set("data", data);
        return root;
    }

    static String id(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("Salt Edge connection id is required");
        }
        return connectionId.trim();
    }
}
