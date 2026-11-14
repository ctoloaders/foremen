package com.foremen.service.dashboard.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.dashboard.GridMath;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.config.RouteWhitelist;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-11-00 task 15.3: the procurement dashboard widgets seeded by changeset
 * {@code 231-seed-procurement-dashboard-widgets.xml} and their role defaults
 * ({@code 232-seed-procurement-dashboard-defaults.xml}). The changesets are parsed straight from the
 * files, every config is validated with the production {@link WidgetConfigValidator} against the real
 * {@link DatasetRegistry}. Read-only, so repeatable without clean-up. The generic fit/overlap/access
 * checks of the role defaults live in {@code DashboardRoleDefaultsSeedMigrationIntegrationTest}.
 *
 * <p><b>Validates: Requirements 24.2, 24.3, 24.4, 24.8</b>
 */
class ProcurementWidgetSeedTest extends AbstractDatasetIntegrationTest {

    static final Path WIDGETS = Path.of("database_files/changesets/231-seed-procurement-dashboard-widgets.xml");
    static final Path DEFAULTS = Path.of("database_files/changesets/232-seed-procurement-dashboard-defaults.xml");
    private static final Path CHANGELOG = Path.of("database_files/changelog.xml");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern INSERT = Pattern.compile(
            "VALUES\\s*\\('([A-Z_]+)',\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*'([^']*)',\\s*'(\\{.*?\\})'::jsonb,"
                    + "\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*'(NONE|SINGLE_PROJECT)',\\s*TRUE,\\s*TRUE", Pattern.DOTALL);
    private static final Pattern DEFAULT_ROW = Pattern.compile("\\('([A-Z]+)',\\s*'([A-Z_]+)',\\s*(\\d+)\\)");

    /** code -> "colSpan x rowSpan" per the 232 design comment. */
    private static final Map<String, String> EXPECTED_SPANS = Map.of(
            "UPCOMING_DELIVERIES", "2x3", "PROCUREMENT_BY_STAGE", "2x1", "POSITIONS_WITH_BUYER", "1x1");

    /** Money / free-text columns of purchase_positions that must never reach a widget. */
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "plannedPurchasePrice", "salePrice", "actualPurchasePrice", "deliveryCostShare", "purchaseVatRate",
            "saleVatRate", "notes", "estimateReference");

    record Seed(String code, String nameRu, String namePl, String descRu, String descPl, String config,
                int configVersion, int colSpan, int rowSpan, String scope) {
    }

    record Default(String role, String widget, int cell) {
    }

    @Autowired
    private DatasetRegistry registry;

    static List<Seed> readSeeds() throws Exception {
        String xml = Files.readString(WIDGETS);
        Matcher m = INSERT.matcher(xml);
        List<Seed> out = new ArrayList<>();
        while (m.find()) {
            out.add(new Seed(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6),
                    Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8)), Integer.parseInt(m.group(9)),
                    m.group(10)));
        }
        return out;
    }

    static List<Default> readDefaults() throws Exception {
        String xml = Files.readString(DEFAULTS);
        Matcher m = DEFAULT_ROW.matcher(xml);
        List<Default> out = new ArrayList<>();
        while (m.find()) {
            out.add(new Default(m.group(1), m.group(2), Integer.parseInt(m.group(3))));
        }
        return out;
    }

    @Test
    @DisplayName("231 seeds exactly the three procurement widgets, idempotent per code, RU/PL names, spans, NONE scope")
    void seedsAreComplete() throws Exception {
        String xml = Files.readString(WIDGETS);
        List<Seed> seeds = readSeeds();
        assertThat(seeds).extracting(Seed::code).containsExactlyInAnyOrderElementsOf(EXPECTED_SPANS.keySet());
        assertThat(xml.split("<changeSet ", -1).length - 1).as("one changeSet per widget").isEqualTo(3);
        for (Seed s : seeds) {
            assertThat(s.nameRu()).as("%s name_ru", s.code()).isNotBlank();
            assertThat(s.namePl()).as("%s name_pl", s.code()).isNotBlank();
            assertThat(s.descRu()).as("%s description_ru", s.code()).isNotBlank();
            assertThat(s.descPl()).as("%s description_pl", s.code()).isNotBlank();
            assertThat(s.colSpan() + "x" + s.rowSpan()).as("%s span", s.code()).isEqualTo(EXPECTED_SPANS.get(s.code()));
            assertThat(s.scope()).isEqualTo("NONE");
            assertThat(s.configVersion()).isEqualTo(1);
            assertThat(xml).as("%s guarded by code precondition", s.code())
                    .contains("SELECT COUNT(*) FROM dashboard_widgets WHERE code = '" + s.code() + "'");
            JsonNode cfg = JSON.readTree(s.config());
            assertThat(cfg.get("dataset").asText()).isEqualTo("PURCHASE_POSITIONS");
            assertThat(cfg.get("schemaVersion").asInt()).isEqualTo(s.configVersion());
            assertThat(cfg.at("/title/ru").asText()).isEqualTo(s.nameRu());
            assertThat(cfg.at("/title/pl").asText()).isEqualTo(s.namePl());
        }
        assertThat(xml).as("MARK_RAN on every changeSet").contains("onFail=\"MARK_RAN\"");
    }

    @Test
    @DisplayName("231 and 232 are the last includes of changelog.xml, in order")
    void registeredLastInChangelog() throws Exception {
        String log = Files.readString(CHANGELOG);
        int p230 = log.indexOf("230-");
        int p231 = log.indexOf("231-seed-procurement-dashboard-widgets.xml");
        int p232 = log.indexOf("232-seed-procurement-dashboard-defaults.xml");
        assertThat(p230).isPositive();
        assertThat(p231).isGreaterThan(p230);
        assertThat(p232).isGreaterThan(p231);
        assertThat(log.indexOf("<include", p232 + 1)).as("nothing registered after 232").isEqualTo(-1);
    }

    @Test
    @DisplayName("every procurement widget config passes the production WidgetConfigValidator")
    void everyConfigIsValid() throws Exception {
        WidgetConfigValidator validator = new WidgetConfigValidator(registry);
        for (Seed s : readSeeds()) {
            WidgetConfigValidator.Result r = validator.validate(s.config(), false);
            assertThat(r.violations()).as("violations of %s", s.code()).isEmpty();
            assertThat(r.config()).isNotNull();
        }
    }

    @Test
    @DisplayName("configs use only dataset fields, none of them money, and links target whitelisted routes")
    void noMoneyFieldsAndWhitelistedLinks() throws Exception {
        Dataset ds = registry.get("PURCHASE_POSITIONS").orElseThrow();
        Set<String> known = new TreeSet<>();
        ds.fields().forEach(f -> known.add(f.name()));
        Set<String> money = new TreeSet<>();
        ds.fields().stream().filter(f -> f.type() == FieldType.MONEY).forEach(f -> money.add(f.name()));
        assertThat(money).as("dataset exposes no money field").isEmpty();

        for (Seed s : readSeeds()) {
            JsonNode cfg = JSON.readTree(s.config());
            Set<String> used = new TreeSet<>();
            for (String arr : List.of("columns", "dimensions", "measures", "filters")) {
                cfg.path(arr).forEach(n -> used.add(n.get("field").asText()));
            }
            cfg.path("sort").forEach(n -> used.add(n.get("by").asText()));
            if (cfg.hasNonNull("dateField")) {
                used.add(cfg.get("dateField").asText());
            }
            assertThat(known).as("%s fields exist in the dataset", s.code()).containsAll(used);
            assertThat(used).as("%s money/forbidden fields", s.code()).doesNotContainAnyElementsOf(FORBIDDEN_FIELDS);
            cfg.path("links").forEach(l -> assertThat(RouteWhitelist.isAllowed(l.get("route").asText()))
                    .as("%s link %s", s.code(), l.get("route")).isTrue());
        }
        assertThat(readSeeds().stream().filter(s -> s.config().contains("\"links\"")).count())
                .as("UPCOMING_DELIVERIES and POSITIONS_WITH_BUYER carry a link").isEqualTo(2);
    }

    @Test
    @DisplayName("232 role defaults: BUYER 3, MANAGER 2, FOREMAN 1, ADMIN 1; fit the grid, no overlap, unique per widget")
    void roleDefaults() throws Exception {
        Map<String, String> spans = new LinkedHashMap<>();
        readSeeds().forEach(s -> spans.put(s.code(), s.colSpan() + "x" + s.rowSpan()));
        Map<String, List<Default>> byRole = new LinkedHashMap<>();
        readDefaults().forEach(d -> byRole.computeIfAbsent(d.role(), k -> new ArrayList<>()).add(d));

        assertThat(byRole.keySet()).containsExactlyInAnyOrder("ADMIN", "MANAGER", "FOREMAN", "BUYER");
        assertThat(widgets(byRole, "BUYER"))
                .containsExactlyInAnyOrder("POSITIONS_WITH_BUYER", "PROCUREMENT_BY_STAGE", "UPCOMING_DELIVERIES");
        assertThat(widgets(byRole, "MANAGER")).containsExactlyInAnyOrder("UPCOMING_DELIVERIES", "PROCUREMENT_BY_STAGE");
        assertThat(widgets(byRole, "FOREMAN")).containsExactly("UPCOMING_DELIVERIES");
        assertThat(widgets(byRole, "ADMIN")).containsExactly("PROCUREMENT_BY_STAGE");

        byRole.forEach((role, list) -> {
            long occupied = 0L;
            for (Default d : list) {
                String[] sp = spans.get(d.widget()).split("x");
                int cols = Integer.parseInt(sp[0]);
                int rows = Integer.parseInt(sp[1]);
                assertThat(GridMath.fits(d.cell(), cols, rows)).as("%s %s@%d fits", role, d.widget(), d.cell())
                        .isTrue();
                long fp = GridMath.footprint(d.cell(), cols, rows);
                assertThat(fp & occupied).as("%s %s@%d overlaps another procurement default", role, d.widget(),
                        d.cell()).isZero();
                occupied |= fp;
            }
        });
    }

    private static List<String> widgets(Map<String, List<Default>> byRole, String role) {
        return byRole.get(role).stream().map(Default::widget).toList();
    }
}
