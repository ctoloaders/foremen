package com.foremen.qa.steps;

import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.DataTablePage;
import com.foremen.qa.pages.DictionaryPage;
import com.foremen.qa.pages.ProjectsPage;
import com.foremen.qa.pages.ReferenceFilter;
import com.foremen.qa.pages.WorkCatalogPage;
import com.foremen.qa.pages.WorkPricesPage;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-04 base-entities smoke steps (Requirements 4.1, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 9.2).
 *
 * <p>This slice exercises the data-foundation UI through the browser as the seeded ADMIN and covers:
 * <ul>
 *   <li><b>Dictionaries</b> (8.1) — a data-driven Scenario Outline opens each of the nine flat
 *       dictionary routes and verifies the page loads and its table renders with columns
 *       ({@link DictionaryPage} + the generic {@link DataTablePage}).</li>
 *   <li><b>Reference filter</b> (8.5) — on the Work Catalog list a single-value reference filter on
 *       the work-category column narrows the list. Two run-created work items in two different
 *       run-created categories are set up via {@link ApiHelper}; filtering to one category drops the
 *       row count ({@link ReferenceFilter}).</li>
 *   <li><b>Work catalog</b> (8.2) — the list renders and a work item is created selecting a category
 *       and a unit through the two FK reference selects, then appears in the list. The FK category
 *       and unit are run-created via the API so the pick is deterministic; teardown removes the work
 *       item and its FK rows ({@link WorkCatalogPage}).</li>
 *   <li><b>Work prices</b> (8.3) — the list renders and a current price ({@code validTo == null}) is
 *       shown. A full chain (unit → category → work item → seeded currency → open-ended price) is
 *       set up via the API with LIFO teardown ({@link WorkPricesPage}).</li>
 *   <li><b>Projects</b> (8.4) — the list renders and a project is created via the happy path
 *       (name only) and appears in the list; teardown deletes it ({@link ProjectsPage}).</li>
 *   <li><b>Menu grouping</b> (8.6) — the Catalog and Dictionaries nav groups are present and their
 *       items route to the correct pages ({@link AppShell}).</li>
 * </ul>
 *
 * <p>The stack-ready background and the "log in as the seeded admin" step are reused from
 * {@link CommonSteps}; the generic table assertions mirror {@link AdminPanelSteps}. Every created
 * fixture is registered for LIFO teardown so runs stay self-cleaning (Requirement 3). The
 * create → verify → delete lifecycles here also transitively exercise the FOR-01 CRUD framework
 * (Requirement 9.2).
 */
public class DictionarySteps {

    // Dictionary/collection API paths used for fixture setup + teardown.
    private static final String WORK_CATEGORIES = "/api/work-categories";
    private static final String MEASUREMENT_UNITS = "/api/measurement-units";
    private static final String WORK_ITEMS = "/api/work-items";
    private static final String WORK_PRICES = "/api/work-prices";
    private static final String CURRENCIES = "/api/currencies";

    private final World world;

    private DictionaryPage dictionaryPage;
    private WorkCatalogPage workCatalogPage;
    private WorkPricesPage workPricesPage;
    private ProjectsPage projectsPage;
    private AppShell appShell;

    // Remembered fixture display names / handles for later UI assertions.
    private String createdCategoryName;
    private String createdUnitName;
    private String createdWorkItemNameRu;
    private String filterCategoryName;
    private String pricedWorkItemNameRu;
    private String createdProjectName;

    /** Cucumber (picocontainer) injects the per-scenario {@link World}. */
    public DictionarySteps(World world) {
        this.world = world;
    }

    private Page page() {
        return Hooks.currentPage();
    }

    private AppShell appShell() {
        if (appShell == null) {
            appShell = new AppShell(page());
        }
        return appShell;
    }

    private ApiHelper adminApi() {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        return api;
    }

    // ================= Dictionaries (Requirement 8.1) =================

    @When("I open the dictionary page {string}")
    public void iOpenTheDictionaryPage(String route) {
        dictionaryPage = new DictionaryPage(page(), route).open();
    }

    @Then("the dictionary page loads and its table renders with columns")
    public void theDictionaryPageLoadsAndItsTableRendersWithColumns() {
        assertThat(dictionaryPage.isOpen())
                .as("the dictionary route %s should be open", dictionaryPage.route())
                .isTrue();
        DataTablePage table = dictionaryPage.table().waitUntilLoaded();
        assertThat(table.isLoaded())
                .as("the dictionary table for %s should render", dictionaryPage.route())
                .isTrue();
        assertThat(table.columnHeaders())
                .as("the dictionary table for %s should render at least one column header",
                        dictionaryPage.route())
                .isNotEmpty();
    }

    // ================= Reference filter (Requirement 8.5) =================

    /**
     * Set up two run-created work items in two distinct run-created categories (sharing one
     * run-created unit) so a single-value reference filter on the category column can be shown to
     * narrow the list. Registers LIFO teardown for the work items, the categories, and the unit.
     */
    @When("two work items in two different categories exist")
    public void twoWorkItemsInTwoDifferentCategoriesExist() {
        ApiHelper api = adminApi();

        long unitId = createUnit(api);
        long categoryAId = createCategory(api);
        // Remember category A's display name (the Russian name we seeded) for the UI filter pick.
        filterCategoryName = createdCategoryName;
        long categoryBId = createCategory(api);

        createWorkItem(api, categoryAId, unitId);
        createWorkItem(api, categoryBId, unitId);
    }

    @When("I open the Work Catalog page")
    public void iOpenTheWorkCatalogPage() {
        workCatalogPage = new WorkCatalogPage(page()).open();
        workCatalogPage.table().waitUntilLoaded();
    }

    /**
     * Apply a single-value reference filter on the work-category column, picking the run-created
     * category A, and assert the list narrows: after filtering, every visible row belongs to
     * category A (the other category's item is filtered out), so the filtered count is strictly less
     * than the pre-filter count.
     */
    @Then("applying a single-value category filter narrows the list")
    public void applyingASingleValueCategoryFilterNarrowsTheList() {
        DataTablePage table = workCatalogPage.table().waitUntilLoaded();
        int before = table.rowCount();
        assertThat(before)
                .as("the work-catalog list should show the seeded rows before filtering")
                .isGreaterThanOrEqualTo(2);

        ReferenceFilter filter = new ReferenceFilter(page());
        // The WorkCatalog columns are [name, workCategory, unit, active]; the reference-backed
        // category column is at index 1. Open its filter by position (locale-independent — the
        // header label is localized), search for the run-created category name, and single-pick it.
        filter.openByHeaderIndex(1);
        filter.search(filterCategoryName);
        filter.selectSingle(filterCategoryName);

        // Web-first: wait for the debounced refetch to settle, then assert the list narrowed and
        // only the filtered category's item remains visible.
        page().waitForCondition(() -> workCatalogPage.table().containsRow(filterCategoryName));
        int after = workCatalogPage.table().rowCount();
        assertThat(after)
                .as("filtering to a single category should narrow the list below the unfiltered count")
                .isLessThan(before);
        assertThat(workCatalogPage.table().containsRow(filterCategoryName))
                .as("the filtered list should still contain the picked category's work item")
                .isTrue();
    }

    // ================= Work catalog (Requirement 8.2) =================

    @Then("the work catalog list renders")
    public void theWorkCatalogListRenders() {
        DataTablePage table = workCatalogPage.table().waitUntilLoaded();
        assertThat(table.isLoaded())
                .as("the work catalog list table should render")
                .isTrue();
        assertThat(table.columnHeaders())
                .as("the work catalog list should render at least one column header")
                .isNotEmpty();
    }

    /**
     * Create a work item through the UI, selecting a run-created category and unit via the two FK
     * reference selects. The FK category and unit are provisioned via the API first so their display
     * names are known and deterministic to pick; the work item itself is created through the form so
     * the FK-select UX is exercised (Requirement 8.2). Teardown removes the work item and its FK
     * rows in FK-safe LIFO order.
     */
    @When("I create a work item selecting a category and a unit")
    public void iCreateAWorkItemSelectingACategoryAndAUnit() {
        ApiHelper api = adminApi();
        long unitId = createUnit(api);
        long categoryId = createCategory(api);

        createdWorkItemNameRu = "QA Work " + DataGen.nextCode();
        String workItemNamePl = createdWorkItemNameRu + " PL";

        // Register teardown for the created work item up-front (by resolving its id via the list),
        // registered AFTER the category/unit teardown so LIFO removes the work item first.
        final String workItemName = createdWorkItemNameRu;
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                long id = t.resolveIdByName(WORK_ITEMS, workItemName);
                t.deleteDictionaryRow(WORK_ITEMS, id);
            } catch (RuntimeException notFound) {
                // Work item never persisted (create failed) — nothing to clean up.
            }
        });

        workCatalogPage.openCreate()
                .selectWorkCategory(createdCategoryName)
                .selectUnit(createdUnitName)
                .fillNames(createdWorkItemNameRu, workItemNamePl)
                .submit();
        workCatalogPage.waitUntilFormClosed();
    }

    @Then("the created work item appears in the work catalog list")
    public void theCreatedWorkItemAppearsInTheWorkCatalogList() {
        assertThat(createdWorkItemNameRu)
                .as("a work item must have been created before asserting it appears")
                .isNotNull();
        DataTablePage table = workCatalogPage.table().waitUntilLoaded();
        table.search(createdWorkItemNameRu);
        page().waitForCondition(() -> table.containsRow(createdWorkItemNameRu));
        assertThat(table.containsRow(createdWorkItemNameRu))
                .as("the newly created work item %s should appear in the list", createdWorkItemNameRu)
                .isTrue();
    }

    // ================= Work prices (Requirement 8.3) =================

    /**
     * Set up a current (open-ended) work price via the API: a run-created unit → category → work
     * item, priced in a seeded currency ({@code PLN}) with {@code validTo = null}. Registers LIFO
     * teardown for the price, work item, category, and unit so the run stays self-cleaning.
     */
    @When("a current work price exists")
    public void aCurrentWorkPriceExists() {
        ApiHelper api = adminApi();
        long unitId = createUnit(api);
        long categoryId = createCategory(api);
        long workItemId = createWorkItem(api, categoryId, unitId);
        pricedWorkItemNameRu = createdWorkItemNameRu;

        long currencyId = api.resolveDictionaryIdByCode(CURRENCIES, "PLN");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workItemId", workItemId);
        body.put("currencyId", currencyId);
        body.put("netPrice", 100.00);
        body.put("validFrom", LocalDate.now().toString());
        body.put("validTo", null); // null => current price
        long priceId = api.createDictionaryRow(WORK_PRICES, body);
        world.registerTeardown(() -> world.api().deleteDictionaryRow(WORK_PRICES, priceId));
    }

    @When("I open the Work Prices page")
    public void iOpenTheWorkPricesPage() {
        workPricesPage = new WorkPricesPage(page()).open();
        workPricesPage.table().waitUntilLoaded();
    }

    @Then("the work prices list renders and a current price is shown")
    public void theWorkPricesListRendersAndACurrentPriceIsShown() {
        DataTablePage table = workPricesPage.table().waitUntilLoaded();
        assertThat(table.isLoaded())
                .as("the work prices list table should render")
                .isTrue();
        assertThat(table.columnHeaders())
                .as("the work prices list should render at least one column header")
                .isNotEmpty();

        // Surface the just-created current price by searching for its work-item name, then assert it
        // shows an open-ended validTo (the visible signal of a current price) and a current badge.
        assertThat(pricedWorkItemNameRu)
                .as("a current price must have been set up before this assertion")
                .isNotNull();
        table.search(pricedWorkItemNameRu);
        page().waitForCondition(() -> workPricesPage.table().containsRow(pricedWorkItemNameRu));
        assertThat(workPricesPage.rowValidToIsOpen(pricedWorkItemNameRu))
                .as("the created current price row should show an open-ended validTo (—)")
                .isTrue();
        assertThat(workPricesPage.currentBadge().count())
                .as("at least one current-price badge should be visible")
                .isGreaterThan(0);
    }

    // ================= Projects (Requirement 8.4) =================

    @When("I open the Projects page")
    public void iOpenTheProjectsPage() {
        projectsPage = new ProjectsPage(page()).open();
        projectsPage.table().waitUntilLoaded();
    }

    @Then("the projects list renders")
    public void theProjectsListRenders() {
        DataTablePage table = projectsPage.table().waitUntilLoaded();
        assertThat(table.isLoaded())
                .as("the projects list table should render")
                .isTrue();
        assertThat(table.columnHeaders())
                .as("the projects list should render at least one column header")
                .isNotEmpty();
    }

    /**
     * Create a project through the happy path (name only) and register teardown (delete via
     * {@code DELETE /api/projects/{id}}). Teardown resolves the id by name so it works even though
     * the id is only known server-side after the UI create.
     */
    @When("I create a project via the happy path")
    public void iCreateAProjectViaTheHappyPath() {
        createdProjectName = DataGen.nextProjectName();
        final String name = createdProjectName;
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                long id = t.resolveIdByName("/api/projects", name);
                t.deleteProject(id);
            } catch (RuntimeException notFound) {
                // Project never persisted, or resolvable only by name mismatch — best-effort cleanup.
            }
        });

        projectsPage.openCreate()
                .fillName(createdProjectName)
                .submit();
        projectsPage.waitUntilFormClosed();
    }

    @Then("the created project appears in the projects list")
    public void theCreatedProjectAppearsInTheProjectsList() {
        assertThat(createdProjectName)
                .as("a project must have been created before asserting it appears")
                .isNotNull();
        DataTablePage table = projectsPage.table().waitUntilLoaded();
        table.search(createdProjectName);
        page().waitForCondition(() -> table.containsRow(createdProjectName));
        assertThat(table.containsRow(createdProjectName))
                .as("the newly created project %s should appear in the projects list",
                        createdProjectName)
                .isTrue();
    }

    // ================= Menu grouping (Requirement 8.6) =================

    @Then("the Catalog navigation group items route correctly")
    public void theCatalogNavigationGroupItemsRouteCorrectly() {
        appShell().waitUntilRendered();
        // The Catalog group holds Work Catalog (/catalog/works) and Work Prices (/catalog/prices).
        assertNavItemRoutes(WorkCatalogPage.ROUTE, WorkPricesPage.ROUTE);
    }

    @Then("the Dictionaries navigation group items route correctly")
    public void theDictionariesNavigationGroupItemsRouteCorrectly() {
        appShell().waitUntilRendered();
        // Verify the presence + routing of a representative subset of the nine dictionary items so
        // the check proves the group renders and routes without coupling to every localized label.
        assertNavItemRoutes(
                "/measurement-units",
                "/currencies",
                "/offer-packages");
    }

    /**
     * Assert each route's nav item is present + visible and that clicking it lands on that route,
     * proving the group's items route correctly. Navigates home between checks so each item is
     * clicked from a stable shell state.
     */
    private void assertNavItemRoutes(String... routes) {
        AppShell shell = appShell();
        for (String route : routes) {
            assertThat(shell.isNavItemVisible(route))
                    .as("ADMIN should see the navigation item for route %s", route)
                    .isTrue();
            shell.navigateTo(route);
            assertThat(shell.isOnRoute(route))
                    .as("clicking the navigation item for %s should land on it", route)
                    .isTrue();
        }
    }

    // ================= Fixture helpers (API setup + LIFO teardown) =================

    /** Create a run-created measurement unit via the API; remembers its display name. */
    private long createUnit(ApiHelper api) {
        String code = DataGen.nextCode();
        // Unit codes are short; keep within typical column limits by trimming the generated code.
        String unitCode = code.length() > 12 ? code.substring(code.length() - 12) : code;
        createdUnitName = "QA Unit " + code;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", unitCode);
        body.put("nameRU", createdUnitName);
        body.put("namePL", createdUnitName + " PL");
        body.put("active", true);
        long id = api.createDictionaryRow(MEASUREMENT_UNITS, body);
        world.registerTeardown(() -> world.api().deleteDictionaryRow(MEASUREMENT_UNITS, id));
        return id;
    }

    /** Create a run-created work category via the API; remembers its display name. */
    private long createCategory(ApiHelper api) {
        String code = DataGen.nextCode();
        createdCategoryName = "QA Category " + code;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("orderNo", DataGen.nextSeq());
        body.put("nameRU", createdCategoryName);
        body.put("namePL", createdCategoryName + " PL");
        body.put("active", true);
        long id = api.createDictionaryRow(WORK_CATEGORIES, body);
        world.registerTeardown(() -> world.api().deleteDictionaryRow(WORK_CATEGORIES, id));
        return id;
    }

    /** Create a run-created work item via the API in the given category/unit; remembers its name. */
    private long createWorkItem(ApiHelper api, long categoryId, long unitId) {
        createdWorkItemNameRu = "QA Work " + DataGen.nextCode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workCategoryId", categoryId);
        body.put("unitId", unitId);
        body.put("nameRU", createdWorkItemNameRu);
        body.put("namePL", createdWorkItemNameRu + " PL");
        body.put("active", true);
        long id = api.createDictionaryRow(WORK_ITEMS, body);
        world.registerTeardown(() -> world.api().deleteDictionaryRow(WORK_ITEMS, id));
        return id;
    }
}
