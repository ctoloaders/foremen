package com.foremen.service.dashboard.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.service.dashboard.config.WidgetConfig;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import com.foremen.service.dashboard.query.DashboardQueryEngine;
import com.foremen.service.dashboard.query.QueryContext;
import com.foremen.service.dashboard.query.WidgetResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-10 task 3.1: the seeded widget library (changeset {@code 221-seed-dashboard-widget-library.xml})
 * is parsed straight from the changeset file, every config is validated with the production
 * {@link WidgetConfigValidator} against the real {@link DatasetRegistry}, and executed against a
 * PostgreSQL Testcontainer as ADMIN (both locales). The test only reads and runs queries, so it is
 * repeatable without clean-up.
 *
 * <p><b>Validates: Requirements 14.1, 14.2, 14.3</b>
 */
class DashboardWidgetLibrarySeedTest extends AbstractDatasetIntegrationTest {

    private static final Path CHANGESET = Path.of("database_files/changesets/221-seed-dashboard-widget-library.xml");
    private static final Path CHANGELOG = Path.of("database_files/changelog.xml");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INSERT = Pattern.compile(
            "VALUES\\s*\\(\\s*'([A-Z_]+)'.*?'(\\{.*\\})'::jsonb,\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*'(NONE|SINGLE_PROJECT)',"
                    + "\\s*TRUE,\\s*TRUE", Pattern.DOTALL);

    /** code -> {colSpan x rowSpan, scope}. Requirement 14.1 table. */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("ACTIVE_PROJECTS", "4x3 NONE");
        EXPECTED.put("PROJECTS_BY_STATUS", "2x2 NONE");
        EXPECTED.put("PROJECT_CARD", "2x2 SINGLE_PROJECT");
        EXPECTED.put("WORK_REPORTS_BY_STATUS", "2x2 NONE");
        EXPECTED.put("WORK_REPORTS_PENDING", "2x3 NONE");
        EXPECTED.put("WORK_REPORTS_TREND", "2x2 NONE");
        EXPECTED.put("MY_WORK_REPORTS", "2x2 NONE");
        EXPECTED.put("PAYMENTS_RECEIVED", "1x1 NONE");
        EXPECTED.put("PAYMENTS_BY_PROJECT", "2x2 NONE");
        EXPECTED.put("PAYROLL_TOTAL", "1x1 NONE");
        EXPECTED.put("PAYROLL_BY_WORKER", "2x3 NONE");
        EXPECTED.put("PURCHASES_BY_PROJECT", "2x2 NONE");
        EXPECTED.put("RECENT_PURCHASES", "2x3 NONE");
        EXPECTED.put("BANK_FLOW", "2x2 NONE");
        EXPECTED.put("BANK_PENDING", "1x1 NONE");
        EXPECTED.put("OFFERS_BY_STATUS", "2x2 NONE");
        EXPECTED.put("USERS_BY_ROLE", "1x2 NONE");
        EXPECTED.put("PROJECT_PAYMENTS", "1x1 SINGLE_PROJECT");
        EXPECTED.put("PROJECT_WORK_REPORTS", "2x2 SINGLE_PROJECT");
        EXPECTED.put("PROJECT_PURCHASES", "2x3 SINGLE_PROJECT");
    }

    private record Seed(String code, String config, String span, String scope, boolean guarded) {
    }

    @Autowired
    private DatasetRegistry registry;
    @Autowired
    private DashboardQueryEngine engine;

    private static List<Seed> readSeeds() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(CHANGESET.toString()));
        NodeList sets = doc.getElementsByTagName("changeSet");
        List<Seed> out = new ArrayList<>();
        for (int i = 0; i < sets.getLength(); i++) {
            Element cs = (Element) sets.item(i);
            String sql = cs.getElementsByTagName("sql").item(0).getTextContent();
            Matcher m = INSERT.matcher(sql);
            assertThat(m.find()).as("changeSet %s insert shape", cs.getAttribute("id")).isTrue();
            String check = cs.getElementsByTagName("sqlCheck").getLength() == 0 ? ""
                    : cs.getElementsByTagName("sqlCheck").item(0).getTextContent();
            boolean guarded = "MARK_RAN".equals(((Element) cs.getElementsByTagName("preConditions").item(0))
                    .getAttribute("onFail"))
                    && check.contains("code = '" + m.group(1) + "'");
            out.add(new Seed(m.group(1), m.group(2), m.group(4) == null ? "" : m.group(4) + "x" + m.group(5),
                    m.group(6), guarded));
        }
        return out;
    }

    @Test
    @DisplayName("changeset seeds exactly the 20 Requirement 14 widgets, idempotent per code, with the right spans/scope")
    void seedsAreComplete() throws Exception {
        List<Seed> seeds = readSeeds();
        assertThat(seeds).extracting(Seed::code).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        assertThat(seeds).allSatisfy(s -> {
            assertThat(s.guarded()).as("%s precondition", s.code()).isTrue();
            assertThat(s.span() + " " + s.scope()).as("%s span/scope", s.code()).isEqualTo(EXPECTED.get(s.code()));
        });
    }

    @Test
    @DisplayName("changeset is registered after 220 in changelog.xml")
    void registeredInChangelog() throws Exception {
        String log = Files.readString(CHANGELOG);
        int p220 = log.indexOf("220-seed-dashboard-widgets-resource.xml");
        int p221 = log.indexOf("221-seed-dashboard-widget-library.xml");
        assertThat(p220).isPositive();
        assertThat(p221).isGreaterThan(p220);
    }

    @Test
    @DisplayName("every seeded config passes the production WidgetConfigValidator")
    void everyConfigIsValid() throws Exception {
        WidgetConfigValidator validator = new WidgetConfigValidator(registry);
        for (Seed s : readSeeds()) {
            WidgetConfigValidator.Result r = validator.validate(s.config(), "SINGLE_PROJECT".equals(s.scope()));
            assertThat(r.violations()).as("violations of %s", s.code()).isEmpty();
            assertThat(r.config()).isNotNull();
        }
    }

    @Test
    @DisplayName("every seeded config executes as ADMIN against the test database in RU and PL")
    void everyConfigExecutes() throws Exception {
        LocalDate from = LocalDate.now().minusYears(1);
        LocalDate to = LocalDate.now();
        for (Seed s : readSeeds()) {
            JsonNode node = JSON.readTree(s.config());
            WidgetConfig cfg = JSON.treeToValue(node, WidgetConfig.class);
            Dataset ds = registry.get(cfg.dataset()).orElseThrow();
            Long bound = "SINGLE_PROJECT".equals(s.scope()) ? 987654321L : null;
            for (String locale : Set.of("ru", "pl")) {
                QueryContext ctx = new QueryContext(1L, "ADMIN", Set.of(), true, from, to, bound, locale);
                WidgetResult result = engine.execute(ds, cfg, ctx);
                assertThat(result).as("%s/%s", s.code(), locale).isNotNull();
                assertThat(result.columns()).as("%s columns", s.code()).isNotEmpty();
            }
        }
    }
}
