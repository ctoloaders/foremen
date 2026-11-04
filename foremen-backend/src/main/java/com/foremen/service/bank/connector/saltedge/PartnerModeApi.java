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
 * FOR-07-04 (Requirements 8.7, 9.1, 9.4, 9.5, 9.7, 9.8; design §Salt Edge connector): the PARTNER mode —
 * Salt Edge Partners API v1 ({@code https://www.saltedge.com/api/partners/v1}) under Salt Edge's PSD2
 * licence; the company is a <b>lead</b> identified by {@code lead-email}.
 *
 * <p>Routes and field names pinned against <a href="https://docs.saltedge.com/partners/v1/">the Partners
 * API v1 reference</a> (fixture {@code saltedge/partner/routes.json}):
 * <ul>
 *   <li>identity — {@code POST /leads {data:{email}}} (create-or-return) → {@code data.customer_id};</li>
 *   <li>session — {@code POST /lead_sessions/create}, {@code POST /lead_sessions/reconnect},
 *       {@code POST /lead_sessions/refresh} (reconnect / refresh carry {@code customer_id} + {@code connection_id}
 *       in the body) → {@code data.redirect_url};</li>
 *   <li>session body — {@code consent{scopes: [account_details, transactions_details (, holder_information)],
 *       from_date}}, {@code attempt{fetch_scopes: [accounts (, holder_info), transactions], from_date, return_to,
 *       custom_fields}}, top-level {@code provider_code}, {@code skip_provider_select}, {@code daily_refresh},
 *       {@code include_fake_providers}, {@code return_connection_id}, {@code return_error_class},
 *       {@code categorization}. Partners fetch scopes have no {@code balance} (covered by {@code accounts});</li>
 *   <li>connection — {@code GET /connections/{id}}; consents {@code GET /consents/{id}?connection_id=} /
 *       {@code GET /consents?connection_id=}; partner consents {@code GET /partner_consents?connection_id=};</li>
 *   <li>holder info — {@code GET /holder_info?connection_id=};</li>
 *   <li>data — {@code GET /accounts?connection_id=}; {@code GET /transactions?connection_id&account_id&from_id};
 *       pending {@code GET /transactions/pending?connection_id&account_id};</li>
 *   <li>providers — {@code GET /providers?country_code&include_fake_providers} (no {@code key_owner} — Partners
 *       never need own keys);</li>
 *   <li>disable — {@code PUT /partner_consents/{id}/revoke?connection_id=} for every active partner consent.
 *       Partners API v1 exposes no connection delete route; the revoked partner consent ends the sharing.</li>
 * </ul>
 */
public class PartnerModeApi implements SaltEdgeModeApi {

    static final String CATEGORIZATION = "business";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SaltEdgeClient client;

    public PartnerModeApi(SaltEdgeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public SaltEdgeMode mode() {
        return SaltEdgeMode.PARTNER;
    }

    // ---------------------------------------------------------------- identity

    /** {@code POST /leads {data:{email: lead-email}}} — create-or-return → {@code data.customer_id}. */
    @Override
    public String ensureIdentity() {
        String email = client.properties().leadEmail();
        if (email == null) {
            throw SaltEdgeModeApi.missingIdentity("lead-email");
        }
        ObjectNode body = NODES.objectNode();
        body.putObject("data").put("email", email);
        String id = text(dataObject(client.post("/leads", body), "POST /leads"), "customer_id");
        if (id == null) {
            throw SaltEdgeErrorMapper.transientFailure("POST /leads returned no customer_id", null);
        }
        return id;
    }

    // ---------------------------------------------------------------- sessions

    @Override
    public SessionUrl createSession(SessionRequest request) {
        ObjectNode data = NODES.objectNode();
        if (request.customerId() != null) {
            data.put("customer_id", request.customerId());
        }
        data.set("consent", consent(request));
        data.set("attempt", attempt(request));
        if (request.providerCode() != null) {
            data.put("provider_code", request.providerCode());
        }
        data.put("skip_provider_select", request.providerCode() != null);
        commonFlags(data, request);
        return session(client.post("/lead_sessions/create", ClientModeApi.wrap(data)), "POST /lead_sessions/create");
    }

    @Override
    public SessionUrl reconnectSession(String connectionId, SessionRequest request) {
        ObjectNode data = NODES.objectNode();
        data.put("customer_id", request.customerId());
        data.put("connection_id", ClientModeApi.id(connectionId));
        data.set("consent", consent(request));
        data.set("attempt", attempt(request));
        commonFlags(data, request);
        return session(client.post("/lead_sessions/reconnect", ClientModeApi.wrap(data)),
                "POST /lead_sessions/reconnect");
    }

    @Override
    public SessionUrl refreshSession(String connectionId, SessionRequest request) {
        ObjectNode data = NODES.objectNode();
        data.put("customer_id", request.customerId());
        data.put("connection_id", ClientModeApi.id(connectionId));
        data.set("attempt", attempt(request));
        data.put("daily_refresh", true);
        data.put("return_connection_id", true);
        data.put("return_error_class", true);
        data.put("categorization", CATEGORIZATION);
        return session(client.post("/lead_sessions/refresh", ClientModeApi.wrap(data)), "POST /lead_sessions/refresh");
    }

    @Override
    public boolean supportsBackgroundRefresh() {
        return false;
    }

    @Override
    public JsonNode backgroundRefresh(String connectionId, SessionRequest request) {
        throw new UnsupportedOperationException("Partners API v1 has no background refresh; Salt Edge daily_refresh applies");
    }

    /** {@code consent{scopes: [account_details, transactions_details (, holder_information)], from_date}}. */
    static ObjectNode consent(SessionRequest request) {
        ObjectNode consent = NODES.objectNode();
        ArrayNode scopes = consent.putArray("scopes").add("account_details");
        if (request.holderInfo()) {
            scopes.add("holder_information");
        }
        scopes.add("transactions_details");
        if (request.fromDate() != null) {
            consent.put("from_date", request.fromDate().toString());
        }
        return consent;
    }

    /** {@code attempt{fetch_scopes: [accounts (, holder_info), transactions], from_date, return_to, custom_fields}}. */
    static ObjectNode attempt(SessionRequest request) {
        ObjectNode attempt = NODES.objectNode();
        ArrayNode scopes = attempt.putArray("fetch_scopes").add("accounts");
        if (request.holderInfo()) {
            scopes.add("holder_info");
        }
        scopes.add("transactions");
        if (request.fromDate() != null) {
            attempt.put("from_date", request.fromDate().toString());
        }
        if (request.returnTo() != null) {
            attempt.put("return_to", request.returnTo());
        }
        ObjectNode custom = attempt.putObject("custom_fields");
        request.customFields().forEach(custom::put);
        return attempt;
    }

    private static void commonFlags(ObjectNode data, SessionRequest request) {
        data.put("daily_refresh", true);
        data.put("include_fake_providers", request.includeFakeProviders());
        data.put("return_connection_id", true);
        data.put("return_error_class", true);
        data.put("categorization", CATEGORIZATION);
    }

    private static SessionUrl session(JsonNode root, String what) {
        JsonNode data = dataObject(root, what);
        String url = text(data, "redirect_url");
        if (url == null) {
            throw SaltEdgeErrorMapper.transientFailure(what + " returned no redirect_url", null);
        }
        return new SessionUrl(url, instant(data, "expires_at"));
    }

    // ---------------------------------------------------------------- connection + consent

    @Override
    public JsonNode connection(String connectionId) {
        String path = "/connections/" + ClientModeApi.id(connectionId);
        return dataObject(client.get(path), "GET " + path);
    }

    @Override
    public ConsentSnapshot consents(JsonNode connection) {
        String connectionId = ClientModeApi.id(text(connection, "id"));
        JsonNode consent = ClientModeApi.latestConsent(client, connectionId, text(connection, "last_consent_id"));
        List<JsonNode> partnerConsents = partnerConsents(connectionId);
        JsonNode partner = partnerConsents.isEmpty() ? null : partnerConsents.get(partnerConsents.size() - 1);
        return new ConsentSnapshot(consent, partner);
    }

    /** {@code GET /partner_consents?connection_id=} (ascending by id ⇒ latest last). */
    List<JsonNode> partnerConsents(String connectionId) {
        return client.getAll("/partner_consents", Map.of("connection_id", connectionId));
    }

    @Override
    public Optional<JsonNode> holderInfo(JsonNode connection) {
        String connectionId = ClientModeApi.id(text(connection, "id"));
        JsonNode data = client.get(SaltEdgeClient.pathWithQuery("/holder_info", Map.of("connection_id", connectionId)))
                .path("data");
        return data.isObject() && !data.isEmpty() ? Optional.of(data) : Optional.empty();
    }

    // ---------------------------------------------------------------- data reads

    @Override
    public List<JsonNode> accounts(String connectionId) {
        return client.getAll("/accounts", Map.of("connection_id", ClientModeApi.id(connectionId)));
    }

    @Override
    public SaltEdgeClient.Page transactions(String connectionId, String accountId, String fromId) {
        return client.getPage("/transactions", ClientModeApi.accountQuery(connectionId, accountId), fromId);
    }

    @Override
    public List<JsonNode> pendingTransactions(String connectionId, String accountId) {
        return client.getAll("/transactions/pending", ClientModeApi.accountQuery(connectionId, accountId));
    }

    @Override
    public List<JsonNode> providers(String countryCode, boolean includeFake) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("country_code", Objects.requireNonNull(countryCode, "countryCode"));
        query.put("include_fake_providers", Boolean.toString(includeFake));
        return client.getAll("/providers", query);
    }

    @Override
    public Optional<List<JsonNode>> providersWithSaltEdgeKeys(String countryCode, boolean includeFake) {
        return Optional.empty();
    }

    // ---------------------------------------------------------------- revoke

    /** Revokes every active partner consent of the connection (mandatory for Live); already-gone ⇒ success. */
    @Override
    public void revokeAndRemove(String connectionId) {
        String id = ClientModeApi.id(connectionId);
        List<JsonNode> consents;
        try {
            consents = partnerConsents(id);
        } catch (ConnectorError e) {
            if (alreadyGone(e)) {
                return;
            }
            throw e;
        }
        for (JsonNode consent : consents) {
            String consentId = text(consent, "id");
            if (consentId == null || "revoked".equals(text(consent, "status"))) {
                continue;
            }
            String path = SaltEdgeClient.pathWithQuery("/partner_consents/" + consentId + "/revoke",
                    Map.of("connection_id", id));
            try {
                client.put(path, null);
            } catch (ConnectorError e) {
                if (!alreadyGone(e)) {
                    throw e;
                }
            }
        }
    }
}
