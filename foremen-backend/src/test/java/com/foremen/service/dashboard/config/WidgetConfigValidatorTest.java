package com.foremen.service.dashboard.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foremen.service.dashboard.appearance.WidgetStyleCatalog;
import com.foremen.service.dashboard.config.DatasetSchema.Field;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.config.WidgetConfig.Visualization;
import com.foremen.service.dashboard.config.WidgetConfigValidator.Result;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit + property tests for {@link WidgetConfigValidator}, {@link RouteWhitelist} and
 * {@link WidgetStyleCatalog}.
 *
 * <p>Feature: FOR-10-dashboard. <b>Validates: Requirements 5.1-5.6, 10.2</b>
 */
class WidgetConfigValidatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final DatasetSchema DS = new DatasetSchema("WORK_REPORTS", true, List.of(
            new Field("id", FieldType.ID, true, true, true, true),
            new Field("status", FieldType.ENUM, true, true, true, false),
            new Field("name", FieldType.STRING, true, true, true, false),
            new Field("hours", FieldType.NUMBER, true, true, false, true),
            new Field("performedFrom", FieldType.DATE, true, true, true, true),
            new Field("secret", FieldType.STRING, false, false, false, false)));

    private static final DatasetSchema PROJECTS = new DatasetSchema("PROJECTS", true, List.of(
            new Field("id", FieldType.ID, true, true, true, true),
            new Field("name", FieldType.STRING, true, true, true, false)));

    private static final DatasetSchemaLookup LOOKUP = code -> switch (code) {
        case "WORK_REPORTS" -> Optional.of(DS);
        case "PROJECTS" -> Optional.of(PROJECTS);
        default -> Optional.empty();
    };

    private final WidgetConfigValidator validator = new WidgetConfigValidator(LOOKUP);

    // ---------------------------------------------------------------- builders

    private static ObjectNode loc(String s) {
        return M.createObjectNode().put("ru", s + " ru").put("pl", s + " pl");
    }

    private static ObjectNode barConfig() {
        ObjectNode n = M.createObjectNode();
        n.put("schemaVersion", 1).put("dataset", "WORK_REPORTS").put("visualization", "BAR");
        n.set("title", loc("t"));
        n.putArray("dimensions").addObject().put("field", "status");
        ObjectNode m = n.putArray("measures").addObject();
        m.put("field", "id").put("agg", "COUNT").put("alias", "cnt");
        m.set("label", loc("cnt"));
        return n;
    }

    private Result run(JsonNode n) {
        return validator.validate(n, false);
    }

    private static List<String> codes(Result r) {
        return r.violations().stream().map(WidgetConfigValidator.Violation::code).toList();
    }

    // ------------------------------------------------------------------- units

    @Test
    void validBarConfigIsAccepted() {
        Result r = run(barConfig());
        assertThat(r.violations()).isEmpty();
        assertThat(r.config().effectiveLimit()).isEqualTo(50);
        assertThat(r.config().effectiveRowScope()).isEqualTo(WidgetConfig.RowScope.PROJECTS);
    }

    @Test
    void unsupportedSchemaVersionAndLimitAreBothReported() {
        ObjectNode n = barConfig();
        n.put("schemaVersion", 2).put("limit", 500);
        assertThat(codes(run(n))).contains("schemaVersion.unsupported", "limit.range");
    }

    @Test
    void unknownTopLevelPropertyIsRejected() {
        ObjectNode n = barConfig();
        n.put("sql", "select 1");
        Result r = run(n);
        assertThat(r.valid()).isFalse();
        assertThat(codes(r)).containsExactly("config.structure");
    }

    @Test
    void unknownDatasetAndFieldAreRejected() {
        ObjectNode n = barConfig();
        n.put("dataset", "NOPE");
        assertThat(codes(run(n))).contains("dataset.unknown");

        ObjectNode n2 = barConfig();
        ((ObjectNode) n2.get("dimensions").get(0)).put("field", "bogus");
        assertThat(codes(run(n2))).contains("field.unknown");
    }

    @Test
    void capabilityMismatchIsRejected() {
        ObjectNode n = barConfig();
        ObjectNode m = (ObjectNode) n.get("measures").get(0);
        m.put("field", "name").put("agg", "SUM");
        assertThat(codes(run(n))).contains("field.capability", "measure.aggType");

        ObjectNode n2 = barConfig();
        ((ObjectNode) n2.get("dimensions").get(0)).put("field", "hours");
        assertThat(codes(run(n2))).contains("field.capability");
    }

    @Test
    void shapeMismatchesAreRejected() {
        ObjectNode pie = barConfig();
        pie.put("visualization", "PIE");
        pie.remove("dimensions");
        assertThat(codes(run(pie))).contains("shape.dimensions");

        ObjectNode kpi = barConfig();
        kpi.put("visualization", "KPI");
        assertThat(codes(run(kpi))).contains("shape.dimensions");
    }

    @Test
    void missingLocalizedStringsAreRejected() {
        ObjectNode n = barConfig();
        ((ObjectNode) n.get("measures").get(0)).remove("label");
        ((ObjectNode) n.get("title")).put("pl", " ");
        assertThat(codes(run(n))).containsOnly("localized.missing");
    }

    @Test
    void styleAndColorsAreChecked() {
        ObjectNode n = barConfig();
        ObjectNode pr = n.putObject("presentation");
        pr.put("style", "STRIPED");
        pr.putArray("colors").add("#2563EB").add("red");
        assertThat(codes(run(n))).containsExactlyInAnyOrder("presentation.style", "presentation.colors");
    }

    @Test
    void linkRouteMustBeWhitelisted() {
        for (String bad : List.of("https://evil.example/x", "javascript:alert(1)", "/admin/secret", "//evil.com")) {
            ObjectNode n = barConfig();
            ObjectNode l = n.putArray("links").addObject();
            l.put("element", "CHART_SEGMENT").put("route", bad);
            assertThat(codes(run(n))).contains("link.routeNotAllowed");
        }
    }

    @Test
    void linkParamsMustBeBoundAndSelected() {
        ObjectNode n = barConfig();
        ObjectNode l = n.putArray("links").addObject();
        l.put("element", "CHART_SEGMENT").put("route", "/projects/{projectId}/{tab}");
        l.putObject("params").put("projectId", "$row.id").put("tab", "nope");
        assertThat(codes(run(n))).contains("link.rowRefNotSelected", "link.tabUnknown");

        ObjectNode n2 = barConfig();
        ObjectNode l2 = n2.putArray("links").addObject();
        l2.put("element", "CHART_SEGMENT").put("route", "/projects/{projectId}");
        assertThat(codes(run(n2))).contains("link.paramUnbound");
    }

    @Test
    void singleProjectLinkUsesProjectReference() {
        ObjectNode n = barConfig();
        ObjectNode l = n.putArray("links").addObject();
        l.put("element", "CHART_SEGMENT").put("route", "/projects/{projectId}/{tab}");
        l.putObject("params").put("projectId", "$project").put("tab", "estimate");
        assertThat(validator.validate(n, true).violations()).isEmpty();
        assertThat(codes(validator.validate(n, false))).contains("link.projectRef");
    }

    @Test
    void oversizedConfigIsRejected() {
        ObjectNode n = barConfig();
        n.set("title", M.createObjectNode().put("ru", "x".repeat(70_000)).put("pl", "y"));
        assertThat(codes(run(n))).containsExactly("config.tooLarge");
        assertThat(codes(validator.validate(n.toString(), false))).containsExactly("config.tooLarge");
    }

    @Test
    void styleCatalogFirstEntryIsFallbackAndHasThreeStyles() {
        for (Visualization v : Visualization.values()) {
            assertThat(WidgetStyleCatalog.stylesFor(v)).hasSizeGreaterThanOrEqualTo(3);
            assertThat(WidgetStyleCatalog.defaultStyle(v)).isEqualTo(WidgetStyleCatalog.stylesFor(v).get(0));
        }
        assertThat(WidgetStyleCatalog.stylesFor(Visualization.KPI)).containsExactly("PLAIN", "CARD", "WITH_TREND");
    }

    // ---------------------------------------------------------------- properties

    @Provide
    Arbitrary<ObjectNode> validConfigs() {
        Arbitrary<Visualization> viz = Arbitraries.of(Visualization.TABLE, Visualization.LIST, Visualization.BAR,
                Visualization.LINE, Visualization.AREA, Visualization.PIE, Visualization.DONUT, Visualization.KPI);
        return viz.flatMap(v -> Arbitraries.integers().between(1, 200).flatMap(limit ->
                Arbitraries.integers().between(0, 2).map(styleIdx -> buildValid(v, limit, styleIdx))));
    }

    private static ObjectNode buildValid(Visualization v, int limit, int styleIdx) {
        ObjectNode n = M.createObjectNode();
        n.put("schemaVersion", 1).put("dataset", "WORK_REPORTS").put("visualization", v.name());
        n.put("limit", limit).put("dateField", "performedFrom");
        n.set("title", loc("t"));
        switch (v) {
            case TABLE, LIST -> {
                var cols = n.putArray("columns");
                cols.addObject().put("field", "name").set("label", loc("n"));
                cols.addObject().put("field", "hours").set("label", loc("h"));
            }
            case KPI -> addMeasure(n);
            default -> {
                n.putArray("dimensions").addObject().put("field", "status");
                addMeasure(n);
            }
        }
        n.putArray("filters").addObject().put("field", "status").put("op", "IN").putArray("value").add("A").add("B");
        n.putArray("sort").addObject().put("by", v == Visualization.TABLE || v == Visualization.LIST ? "name" : "cnt")
                .put("dir", "DESC");
        List<String> styles = WidgetStyleCatalog.stylesFor(v);
        ObjectNode pr = n.putObject("presentation");
        pr.put("style", styles.get(styleIdx % styles.size()));
        pr.putArray("colors").add("#2563EB").add("#16A34A");
        return n;
    }

    private static void addMeasure(ObjectNode n) {
        ObjectNode m = n.putArray("measures").addObject();
        m.put("field", "hours").put("agg", "SUM").put("alias", "cnt");
        m.set("label", loc("c"));
    }

    @Property
    void validGeneratedConfigsAreAccepted(@ForAll("validConfigs") ObjectNode cfg) {
        Result r = run(cfg);
        assertThat(r.violations()).isEmpty();
        assertThat(r.config()).isNotNull();
    }

    @Property
    void unknownTopLevelFieldIsAlwaysRejected(@ForAll("validConfigs") ObjectNode cfg,
                                              @ForAll("unknownNames") String name) {
        cfg.put(name, "x");
        assertThat(run(cfg).valid()).isFalse();
    }

    @Provide
    Arbitrary<String> unknownNames() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(12).map(s -> "zz" + s);
    }

    @Property
    void unknownFieldReferenceIsAlwaysRejected(@ForAll("validConfigs") ObjectNode cfg,
                                               @ForAll("unknownNames") String name) {
        if (cfg.has("columns")) {
            ((ObjectNode) cfg.get("columns").get(0)).put("field", name);
        } else {
            ((ObjectNode) cfg.get("measures").get(0)).put("field", name);
        }
        assertThat(codes(run(cfg))).contains("field.unknown");
    }

    @Property
    void nonWhitelistedRouteIsAlwaysRejected(@ForAll("validConfigs") ObjectNode cfg,
                                             @ForAll("badRoutes") String route) {
        ObjectNode l = cfg.putArray("links").addObject();
        l.put("element", cfg.has("columns") ? "ROW" : cfg.get("visualization").asText().equals("KPI")
                ? "KPI" : "CHART_SEGMENT").put("route", route);
        assertThat(codes(run(cfg))).contains("link.routeNotAllowed");
        assertThat(RouteWhitelist.isAllowed(route)).isFalse();
    }

    @Provide
    Arbitrary<String> badRoutes() {
        Arbitrary<String> ext = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)
                .flatMap(s -> Arbitraries.of("https://" + s + ".com", "http://" + s, "javascript:" + s,
                        "//" + s, "/x/" + s, "/projects/{projectId}/" + s + "/extra", "/users/" + s));
        return ext;
    }

    @Property
    void whitelistedRoutesAreAllowed(@ForAll("goodRoutes") String route) {
        assertThat(RouteWhitelist.isAllowed(route)).isTrue();
    }

    @Provide
    Arbitrary<String> goodRoutes() {
        return Arbitraries.of(RouteWhitelist.ROUTES.toArray(new String[0]));
    }

    @Property
    void styleOutsideCatalogIsAlwaysRejected(@ForAll("validConfigs") ObjectNode cfg,
                                             @ForAll("unknownNames") String style) {
        ((ObjectNode) cfg.get("presentation")).put("style", style);
        assertThat(codes(run(cfg))).contains("presentation.style");
    }
}
