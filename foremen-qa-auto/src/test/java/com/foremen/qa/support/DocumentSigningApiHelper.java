package com.foremen.qa.support;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
public final class DocumentSigningApiHelper implements AutoCloseable {
    private final Playwright playwright;
    private final APIRequestContext request;
    private String bearer;

    private DocumentSigningApiHelper(Playwright playwright, APIRequestContext request) {
        this.playwright = playwright;
        this.request = request;
    }
    public static DocumentSigningApiHelper create() {
        Playwright pw = Playwright.create();
        APIRequestContext req = pw.request().newContext(
                new APIRequest.NewContextOptions().setBaseURL(TestConfig.apiUrl()));
        return new DocumentSigningApiHelper(pw, req);
    }
    public String loginAs(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        APIResponse response = request.post("/api/auth/login", RequestOptions.create().setData(body));
        if (!response.ok()) {
            throw new IllegalStateException("login failed: HTTP " + response.status() + " " + safeBody(response));
        }
        this.bearer = str(JsonParser.parseString(response.text()).getAsJsonObject(), "accessToken"); return bearer;
    }

    public String loginAdmin() {
        return loginAs(TestConfig.adminEmail(), TestConfig.adminPassword());
    }
    public void useBearer(String token) {
        this.bearer = token;
    }
    public String bearer() {
        return bearer;
    }
    public long resolveDocumentTypeId(String code) {
        APIResponse response = request.get("/api/signable-document-types",
                auth().setQueryParam("query", "code==" + code).setQueryParam("size", "200"));
        JsonObject json = okJson(response, "GET /api/signable-document-types");
        if (json.has("content") && json.get("content").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("content")) {
                JsonObject row = el.getAsJsonObject();
                if (code.equalsIgnoreCase(str(row, "code"))) { return lng(row, "id"); }
            }
        }
        throw new IllegalStateException("No signable_document_type with code " + code);
    }

    public long createDocument(long projectId, long documentTypeId, String title) {
        APIResponse response = request.post("/api/signable-documents",
                auth().setData(createBody(projectId, documentTypeId, title)));
        return lng(okJson(response, "POST /api/signable-documents"), "id");
    }
    public int tryCreateDocument(long projectId, long documentTypeId, String title) {
        return request.post("/api/signable-documents",
                auth().setData(createBody(projectId, documentTypeId, title))).status();
    }
    private Map<String, Object> createBody(long projectId, long documentTypeId, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("documentTypeId", documentTypeId);
        body.put("templateLocale", "PL");
        body.put("title", title);
        return body;
    }

    public int requestSignatures(long documentId, String method, String level, List<Signer> signers) {
        List<Map<String, Object>> signerBodies = new ArrayList<>();
        for (Signer s : signers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("signerUserId", s.userId());
            m.put("signerRole", s.role());
            signerBodies.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("level", level);
        body.put("signers", signerBodies);
        return request.post("/api/signable-documents/" + documentId + "/request-signatures",
                auth().setData(body)).status();
    }
    public int signViaScan(long documentId, FilePayload file) {
        return multipart("/api/signable-documents/" + documentId + "/sign", file);
    }
    public int signViaTablet(long documentId, FilePayload file) {
        return multipart("/api/signable-documents/" + documentId + "/sign-tablet", file);
    }

    public int signViaScanWithoutEvidence(long documentId) {
        return request.post("/api/signable-documents/" + documentId + "/sign", auth()).status();
    }
    public int signViaProvider(long documentId, Long signerUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerRef", null);
        body.put("signerUserId", signerUserId);
        return request.post("/api/signable-documents/" + documentId + "/sign-provider", auth().setData(body)).status();
    }
    public int providerCallback(String providerRef, String outcome, String evidenceUri, String contentHash, String declineReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerRef", providerRef);
        body.put("outcome", outcome);
        body.put("evidenceUri", evidenceUri);
        body.put("contentHash", contentHash);
        body.put("declineReason", declineReason);
        return request.post("/api/signatures/callback", RequestOptions.create().setData(body)).status();
    }

    public int decline(long documentId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return request.post("/api/signable-documents/" + documentId + "/decline", auth().setData(body)).status();
    }
    public int voidDocument(long documentId) {
        return request.post("/api/signable-documents/" + documentId + "/void", auth()).status();
    }
    public int generate(long documentId) {
        return request.post("/api/signable-documents/" + documentId + "/generate", auth()).status();
    }
    public int saveBody(long documentId, String bodyText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("body", bodyText);
        return request.put("/api/signable-documents/" + documentId + "/body", auth().setData(body)).status();
    }

    public Doc getDocument(long documentId) {
        APIResponse response = request.get("/api/signable-documents/" + documentId, auth());
        return Doc.from(okJson(response, "GET /api/signable-documents/" + documentId));
    }
    public int tryGetDocument(long documentId) {
        return request.get("/api/signable-documents/" + documentId, auth()).status();
    }
    public String getDocumentRawBody(long documentId) {
        APIResponse response = request.get("/api/signable-documents/" + documentId, auth());
        expectOk(response, "GET /api/signable-documents/" + documentId);
        return response.text();
    }
    @Override
    public void close() {
        try {
            request.dispose();
        } finally {
            playwright.close();
        }
    }

    private int multipart(String path, FilePayload file) {
        return request.post(path, auth().setMultipart(FormData.create().set("file", file))).status();
    }
    private RequestOptions auth() {
        RequestOptions options = RequestOptions.create();
        if (bearer != null) {
            options.setHeader("Authorization", "Bearer " + bearer);
        }
        return options;
    }
    private static JsonObject okJson(APIResponse response, String what) {
        expectOk(response, what);
        return JsonParser.parseString(response.text()).getAsJsonObject();
    }
    private static void expectOk(APIResponse response, String what) {
        if (!response.ok()) {
            throw new IllegalStateException(what + " failed: HTTP " + response.status() + " " + safeBody(response));
        }
    }

    private static String safeBody(APIResponse response) {
        try {
            return response.text();
        } catch (RuntimeException e) {
            return "<no body>";
        }
    }
    private static String str(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : null;
    }
    private static long lng(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new IllegalStateException("Expected numeric " + field + " in: " + json);
        }
        return json.get(field).getAsLong();
    }
    /** One designated signer in a request-signatures call: by user id, by role, or both. */
    public record Signer(Long userId, String role) {
        public static Signer byRole(String role) { return new Signer(null, role); }
        public static Signer byUser(long userId) { return new Signer(userId, null); }
    }

    /** A parsed, read-only projection of the client-reachable SignableDocumentDto. */
    public record Doc(long id, String status, String contentHash, String documentUri,
                      int signedCount, int totalCount, boolean allSigned, List<Sig> signatures) {
        static Doc from(JsonObject json) {
            int signed = 0;
            int total = 0;
            boolean all = false;
            if (json.has("progress") && json.get("progress").isJsonObject()) {
                JsonObject p = json.getAsJsonObject("progress");
                signed = p.has("signedCount") ? p.get("signedCount").getAsInt() : 0;
                total = p.has("totalCount") ? p.get("totalCount").getAsInt() : 0;
                all = p.has("allSigned") && p.get("allSigned").getAsBoolean();
            }
            List<Sig> sigs = new ArrayList<>();
            if (json.has("signatures") && json.get("signatures").isJsonArray()) {
                for (JsonElement el : json.getAsJsonArray("signatures")) {
                    sigs.add(Sig.from(el.getAsJsonObject()));
                }
            }
            return new Doc(lng(json, "id"), str(json, "status"), str(json, "contentHash"),
                    str(json, "documentUri"), signed, total, all, sigs);
        }

        public Sig firstPending(String method) {
            for (Sig s : signatures) {
                if ("PENDING".equals(s.status()) && method.equals(s.method())) { return s; }
            }
            return null;
        }
        public Sig firstPending() {
            for (Sig s : signatures) {
                if ("PENDING".equals(s.status())) { return s; }
            }
            return null;
        }
    }
    /** One designated signature row of a Doc. */
    public record Sig(long id, Long signerUserId, String signerRole, String method,
                      String status, String providerRef) {
        static Sig from(JsonObject s) {
            Long uid = s.has("signerUserId") && !s.get("signerUserId").isJsonNull()
                    ? s.get("signerUserId").getAsLong() : null;
            return new Sig(lng(s, "id"), uid, str(s, "signerRole"), str(s, "method"),
                    str(s, "status"), str(s, "providerRef"));
        }
    }
}

