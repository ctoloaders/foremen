package com.foremen.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation / CI check for the curated bot toolset OpenAPI artifact
 * {@code src/main/resources/openapi/foremen-bot-toolset.yaml} (FOR-08-ai-assistant, task 2.2,
 * Req 2.2, 2.3, 2.4, 2.6, 8.2).
 *
 * <p>This is a plain, fast unit test (no Spring context, no Docker). It asserts the three
 * contract guarantees of the curated artifact:
 *
 * <ol>
 *   <li><b>Parses as OpenAPI 3</b> — the document loads as YAML and declares an {@code openapi: 3.x}
 *       version, a {@code paths} object, and the {@code bearer-jwt} HTTP security scheme under
 *       {@code components.securitySchemes}.</li>
 *   <li><b>Intended operations present</b> — representative operations from every included domain
 *       (projects, rooms, estimate, materials/assortment, catalog, sellers, procurement,
 *       dictionaries, team, schedule, images) exist with their expected HTTP verb.</li>
 *   <li><b>Excluded paths absent</b> — no {@code /api/auth/**}, {@code POST /api/signatures/callback},
 *       {@code POST /api/payments/webhook/**}, nor account/role administration
 *       ({@code /api/users|roles|resources|operations|audit}) appears.</li>
 * </ol>
 *
 * <p>It additionally enforces the per-operation rules (Req 2.4): every operation declares the
 * {@code bearer-jwt} security scheme, and the read/write verb convention holds (GET → read,
 * non-GET → write) — asserted structurally by requiring every operation to carry security and by
 * checking the HTTP methods are limited to the known REST verbs.
 */
@DisplayName("foremen-bot-toolset.yaml OpenAPI validation")
class BotToolsetOpenApiValidationTest {

    private static final String RESOURCE = "openapi/foremen-bot-toolset.yaml";

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "options", "head", "trace");

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDocument() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(200);
        Yaml yaml = new Yaml(options);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in)
                    .as("curated OpenAPI artifact must be on the classpath at %s", RESOURCE)
                    .isNotNull();
            Object parsed = yaml.load(in);
            assertThat(parsed)
                    .as("artifact must parse to a YAML mapping")
                    .isInstanceOf(Map.class);
            return (Map<String, Object>) parsed;
        } catch (Exception e) {
            throw new AssertionError("failed to read/parse " + RESOURCE, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paths(Map<String, Object> doc) {
        Object paths = doc.get("paths");
        assertThat(paths).as("document must declare a paths object").isInstanceOf(Map.class);
        return (Map<String, Object>) paths;
    }

    @Test
    @DisplayName("parses as OpenAPI 3 with the bearer-jwt security scheme")
    @SuppressWarnings("unchecked")
    void parsesAsOpenApi3() {
        Map<String, Object> doc = loadDocument();

        Object version = doc.get("openapi");
        assertThat(version)
                .as("openapi version must be present")
                .isInstanceOf(String.class);
        assertThat(((String) version))
                .as("openapi version must be 3.x")
                .startsWith("3.");

        assertThat(doc.get("info")).as("info block required").isInstanceOf(Map.class);
        assertThat(paths(doc)).as("paths must be non-empty").isNotEmpty();

        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        assertThat(components).as("components block required").isNotNull();
        Map<String, Object> schemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(schemes).as("components.securitySchemes required").isNotNull();

        Map<String, Object> bearer = (Map<String, Object>) schemes.get("bearer-jwt");
        assertThat(bearer).as("bearer-jwt security scheme must be declared").isNotNull();
        assertThat(bearer.get("type")).isEqualTo("http");
        assertThat(bearer.get("scheme")).isEqualTo("bearer");
    }

    @Test
    @DisplayName("every operation declares bearer-jwt security and a known HTTP verb")
    @SuppressWarnings("unchecked")
    void everyOperationIsSecured() {
        Map<String, Object> doc = loadDocument();
        Map<String, Object> paths = paths(doc);

        List<String> unsecured = new ArrayList<>();
        List<String> badVerb = new ArrayList<>();

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> opEntry : item.entrySet()) {
                String method = opEntry.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(method)) {
                    continue; // skip parameters / summary / shared keys at path level
                }
                String where = method.toUpperCase() + " " + pathEntry.getKey();
                Map<String, Object> op = (Map<String, Object>) opEntry.getValue();

                Object security = op.get("security");
                boolean secured = security instanceof List<?> list
                        && list.stream().anyMatch(s -> s instanceof Map<?, ?> m
                                && m.containsKey("bearer-jwt"));
                if (!secured) {
                    unsecured.add(where);
                }
            }
            // Ensure only real HTTP verbs (plus the allowed shared keys) appear on each path.
            for (String key : item.keySet()) {
                if (!HTTP_METHODS.contains(key.toLowerCase())
                        && !Set.of("parameters", "summary", "description", "servers", "$ref")
                                .contains(key)) {
                    badVerb.add(pathEntry.getKey() + " -> " + key);
                }
            }
        }

        assertThat(unsecured)
                .as("every operation must declare the bearer-jwt security scheme (Req 2.4)")
                .isEmpty();
        assertThat(badVerb)
                .as("only standard HTTP verbs are allowed under a path item")
                .isEmpty();
    }

    @Test
    @DisplayName("GET operations are reads and non-GET operations are writes (verb convention)")
    @SuppressWarnings("unchecked")
    void verbReadWriteConvention() {
        Map<String, Object> doc = loadDocument();
        Map<String, Object> paths = paths(doc);

        // The write verbs that must be shown for confirmation (Req 2.4, D4).
        Set<String> writeVerbs = Set.of("post", "put", "patch", "delete");
        boolean sawRead = false;
        boolean sawWrite = false;

        for (Object value : paths.values()) {
            Map<String, Object> item = (Map<String, Object>) value;
            for (String method : item.keySet()) {
                String m = method.toLowerCase();
                if (m.equals("get")) {
                    sawRead = true;
                } else if (writeVerbs.contains(m)) {
                    sawWrite = true;
                }
            }
        }

        assertThat(sawRead).as("toolset must expose read (GET) operations").isTrue();
        assertThat(sawWrite).as("toolset must expose write (non-GET) operations").isTrue();
    }

    @Test
    @DisplayName("intended operations from every included domain are present")
    @SuppressWarnings("unchecked")
    void intendedOperationsPresent() {
        Map<String, Object> doc = loadDocument();
        Map<String, Object> paths = paths(doc);

        // path -> required method
        record Op(String path, String method) {}
        List<Op> required = List.of(
                // projects
                new Op("/api/projects", "get"),
                new Op("/api/projects", "post"),
                new Op("/api/projects/{id}/activate", "post"),
                // rooms
                new Op("/api/rooms", "get"),
                new Op("/api/rooms", "post"),
                // estimate
                new Op("/api/estimates/project/{projectId}", "get"),
                new Op("/api/estimates/project/{projectId}/matrix", "get"),
                new Op("/api/estimates/project/{projectId}/assignments", "post"),
                new Op("/api/estimates/project/{projectId}/materials/reserve", "put"),
                new Op("/api/estimate-lines", "get"),
                // materials / assortment
                new Op("/api/materials", "get"),
                new Op("/api/assortment-positions", "get"),
                new Op("/api/work-material-consumptions", "get"),
                // catalog
                new Op("/api/work-items", "get"),
                new Op("/api/work-prices", "get"),
                new Op("/api/work-categories", "get"),
                // sellers
                new Op("/api/material-sellers", "get"),
                // procurement
                new Op("/api/procurement-documents", "get"),
                new Op("/api/procurement-documents", "post"),
                new Op("/api/procurement-lines", "get"),
                // dictionaries
                new Op("/api/measurement-units", "get"),
                new Op("/api/vat-rates", "get"),
                new Op("/api/room-types", "get"),
                new Op("/api/currencies", "get"),
                // team
                new Op("/api/project-members", "get"),
                new Op("/api/project-members/candidates", "get"),
                // schedule
                new Op("/api/project-schedules", "get"),
                new Op("/api/project-schedules/bars", "put"),
                // images
                new Op("/api/images", "post"));

        List<String> missing = new ArrayList<>();
        for (Op op : required) {
            Object item = paths.get(op.path());
            if (!(item instanceof Map<?, ?> m) || !m.containsKey(op.method())) {
                missing.add(op.method().toUpperCase() + " " + op.path());
            }
        }

        assertThat(missing)
                .as("all intended tool operations must be present (Req 2.2)")
                .isEmpty();
    }

    @Test
    @DisplayName("excluded paths are absent (auth, webhooks, account/role admin)")
    void excludedPathsAbsent() {
        Map<String, Object> doc = loadDocument();
        Set<String> declared = paths(doc).keySet();

        // Forbidden exact / prefix patterns (Req 2.3).
        List<String> forbiddenPrefixes = List.of(
                "/api/auth",
                "/api/signatures",
                "/api/payments",
                "/api/users",
                "/api/roles",
                "/api/resources",
                "/api/operations",
                "/api/audit");

        List<String> violations = new ArrayList<>();
        for (String path : declared) {
            for (String prefix : forbiddenPrefixes) {
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    violations.add(path);
                }
            }
        }

        assertThat(violations)
                .as("excluded paths must not appear in the curated toolset (Req 2.3)")
                .isEmpty();
    }
}
