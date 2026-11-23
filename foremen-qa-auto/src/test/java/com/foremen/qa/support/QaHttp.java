package com.foremen.qa.support;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;

/**
 * HTTP client for the FOR-05-07b offers slice (FOR-QA-AUTO-05-07B). Every call takes the bearer
 * token explicitly, so one scenario can act as ADMIN, CLIENT, a second client or anonymously.
 */
public final class QaHttp implements AutoCloseable {

    private final Playwright playwright;
    private final APIRequestContext request;

    private QaHttp(Playwright playwright, APIRequestContext request) {
        this.playwright = playwright;
        this.request = request;
    }

    public static QaHttp create() {
        Playwright pw = Playwright.create();
        APIRequestContext req = pw.request().newContext(
                new APIRequest.NewContextOptions().setBaseURL(TestConfig.apiUrl()));
        return new QaHttp(pw, req);
    }

    /** Password login; returns the access token. */
    public String login(String email, String password) {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        return token(call("POST", "/api/auth/login", null, body, null));
    }

    public void otpRequest(String email) {
        call("POST", "/api/auth/otp/request", null, "{\"email\":\"" + email + "\"}", null);
    }

    public String otpVerify(String email, String code) {
        String body = "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}";
        return token(call("POST", "/api/auth/otp/verify", null, body, null));
    }

    private static String token(Resp r) {
        if (r.status() != 200) {
            throw new IllegalStateException("auth failed: HTTP " + r.status() + " " + r.abbreviate());
        }
        return r.obj().get("accessToken").getAsString();
    }

    public Resp get(String path, String token) {
        return call("GET", path, token, null, null);
    }

    public Resp post(String path, String token, String jsonBody) {
        return call("POST", path, token, jsonBody, null);
    }

    /** Generic call; jsonBody is raw JSON or null, lang is an Accept-Language value or null. */
    public Resp call(String method, String path, String token, String jsonBody, String lang) {
        RequestOptions opts = RequestOptions.create().setMethod(method);
        if (token != null) {
            opts.setHeader("Authorization", "Bearer " + token);
        }
        if (lang != null) {
            opts.setHeader("Accept-Language", lang);
        }
        if (jsonBody != null) {
            opts.setHeader("Content-Type", "application/json");
            opts.setData(jsonBody);
        }
        return Resp.from(request.fetch(path, opts));
    }

    @Override
    public void close() {
        try {
            request.dispose();
        } finally {
            playwright.close();
        }
    }

    /** Status, lower-cased headers, raw bytes and text of a response. */
    public record Resp(int status, Map<String, String> headers, byte[] bytes, String text) {

        static Resp from(APIResponse response) {
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().forEach((k, v) -> headers.put(k.toLowerCase(), v));
            byte[] bytes = response.body();
            return new Resp(response.status(), headers, bytes, new String(bytes, StandardCharsets.UTF_8));
        }

        /** Parsed JSON body, or null when the body is empty or not JSON. */
        public JsonElement json() {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return JsonParser.parseString(text);
            } catch (RuntimeException e) {
                return null;
            }
        }

        public JsonObject obj() {
            JsonElement j = json();
            if (j == null || !j.isJsonObject()) {
                throw new IllegalStateException("Expected a JSON object, got: " + abbreviate());
            }
            return j.getAsJsonObject();
        }

        public String header(String name) {
            return headers.get(name.toLowerCase());
        }

        /** True when the body starts with the PDF magic bytes. */
        public boolean isPdf() {
            return bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
        }

        public String abbreviate() {
            return text.length() > 400 ? text.substring(0, 400) + "..." : text;
        }
    }
}
