package com.foremen.qa.steps;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.fixtures.WorkReportFixture;
import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.LoginPage;
import com.foremen.qa.pages.ProjectWorkspacePage;
import com.foremen.qa.pages.WorkReportMatrix;
import com.foremen.qa.pages.WorkReportingTab;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.WorkReportApiHelper;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-06-01 UI steps for the FOR-06-01 work-reporting panel (Part B of test-cases.md),
 * reworked for the Amendment A1 matrix batch-authoring surface.
 *
 * <p>Seeds a reportable ACTIVE project via the admin API + the {@link WorkReportFixture} direct-DB
 * fixture (same backbone as the API slice), then drives the browser: workspace-tab navigation, the
 * matrix surface (worker pick -> progress load -> cell entry -> draft accumulation -> bulk save ->
 * progress refresh, plus the mobile collapsible draft), the foreman review actions, and the WORKER
 * own-reports scope. The old single-report {@code WorkReportBuilder} entry point was removed by
 * Amendment A1, so the "new report" action now opens {@link WorkReportMatrix}. Repeatability:
 * {@link DataGen} run-id + LIFO teardown in {@link World}.
 */
public class WorkReportingUiSteps {

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();
    private final WorkReportFixture reportFixture = new WorkReportFixture();

    private Long projectId;
    private TestUser worker;
    private TestUser worker2;
    private TestUser foreman;
    private TestUser manager;
    private WorkReportFixture.WorkItemRef workItem;
    private WorkReportFixture.SeededEstimate estimate;

    private WorkReportingTab tab;
    private WorkReportMatrix matrix;

    public WorkReportingUiSteps(World world) {
        this.world = world;
    }

    private Page page() {
        return Hooks.currentPage();
    }

    private WorkReportingTab tab() {
        if (tab == null) {
            tab = new WorkReportingTab(page());
        }
        return tab;
    }

    private WorkReportMatrix matrix() {
        if (matrix == null) {
            matrix = new WorkReportMatrix(page());
        }
        return matrix;
    }

    private ApiHelper adminApi() {
        ApiHelper a = world.api();
        if (a.accessToken() == null) {
            a.loginAdmin();
        }
        return a;
    }

    private TestUser createUser(String roleCode) {
        TestUser u = userFixture.createActiveUser(roleCode);
        world.registerTeardown(() -> userFixture.deleteUserAndResources(u));
        return u;
    }

    // ---- Setup ----

    @Given("a reportable active project with a crew and a priced estimate exists for the UI")
    public void aReportableProjectForUi() {
        ApiHelper admin = adminApi();
        worker = createUser("WORKER");
        worker2 = createUser("WORKER");
        foreman = createUser("FOREMAN");
        manager = createUser("MANAGER");
        TestUser client = createUser("CLIENT");

        long managerRoleId = admin.resolveRoleIdByCode("MANAGER");
        long foremanRoleId = admin.resolveRoleIdByCode("FOREMAN");
        long workerRoleId = admin.resolveRoleIdByCode("WORKER");
        long clientRoleId = admin.resolveRoleIdByCode("CLIENT");

        List<Map<String, Object>> members = new ArrayList<>();
        members.add(member(manager.id(), managerRoleId));
        members.add(member(client.id(), clientRoleId));
        String name = DataGen.nextProjectName();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("members", members);
        long pid = admin.createProject(body);
        this.projectId = pid;
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(pid);
            } catch (RuntimeException ignored) {
            }
        });

        admin.assignProjectMember(worker.id(), pid, workerRoleId);
        admin.assignProjectMember(worker2.id(), pid, workerRoleId);
        admin.assignProjectMember(foreman.id(), pid, foremanRoleId);

        long roomTypeId = admin.resolveFirstRowId("/api/room-types");
        long roomA = createRoom(admin, pid, roomTypeId, "Room A " + DataGen.runId());
        long roomB = createRoom(admin, pid, roomTypeId, "Room B " + DataGen.runId());
        long roomNoPlan = createRoom(admin, pid, roomTypeId, "Room NoPlan " + DataGen.runId());

        this.workItem = reportFixture.firstWorkItemWithCategory();
        this.estimate = reportFixture.seedEstimate(pid, workItem, new BigDecimal("12.50"),
                roomA, new BigDecimal("20"), roomB, new BigDecimal("5"), roomNoPlan);
        world.registerTeardown(() -> reportFixture.deleteEstimate(estimate.estimateId()));

        long workerMemberId = reportFixture.projectMemberId(worker.id(), pid);
        reportFixture.linkMemberWorkCategory(workerMemberId, workItem.workCategoryId());

        admin.loginAdmin();
        admin.updateProjectStatus(pid, name, "ACTIVE");
    }

    private static Map<String, Object> member(long userId, long roleId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("projectRoleId", roleId);
        return m;
    }

    private long createRoom(ApiHelper admin, long pid, long roomTypeId, String label) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", pid);
        body.put("roomTypeId", roomTypeId);
        body.put("label", label);
        body.put("ceilingHeight", 2.5);
        body.put("floorArea", 10);
        body.put("wallArea", 25);
        return admin.createRoom(body);
    }

    private long requireProject() {
        if (projectId == null) {
            throw new IllegalStateException("No UI project in scope; the Given must run first.");
        }
        return projectId;
    }

    /** Seed a SUBMITTED report for the worker via the API so the review/scope UI has a row. */
    private long seedSubmittedWorkerReport() {
        WorkReportApiHelper rapi = WorkReportApiHelper.create();
        try {
            rapi.loginAs(worker.email(), worker.password());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", requireProject());
            body.put("workItemId", workItem.workItemId());
            body.put("workerId", worker.id());
            body.put("performedFrom", "2026-01-01");
            body.put("performedTo", "2026-01-02");
            body.put("note", "ui " + DataGen.runId());
            Map<String, Object> room = new LinkedHashMap<>();
            room.put("roomId", estimate.roomWithPlanAId());
            room.put("quantity", new BigDecimal("3"));
            room.put("inputMode", "ABSOLUTE");
            body.put("rooms", List.of(room));
            WorkReportApiHelper.Resp created = rapi.create(body);
            assertThat(created.status()).as("seed SUBMITTED report (body %s)", created.text())
                    .isEqualTo(200);
            long id = created.obj().get("id").getAsLong();
            world.registerTeardown(() -> reportFixture.deleteWorkReport(id));
            return id;
        } finally {
            rapi.close();
        }
    }

    private void loginUi(TestUser u) {
        Page page = page();
        LoginPage login = new LoginPage(page);
        login.open();
        login.login(u.email(), u.password());
        page.waitForURL(url -> !url.contains(LoginPage.ROUTE));
        new AppShell(page).waitUntilRendered();
    }

    private void openWorkReportingTab() {
        new ProjectWorkspacePage(page()).openTab(requireProject(), "workReporting");
        tab().waitUntilRendered();
    }

    // ---- Navigation / panel steps ----

    @Given("a submitted report by the worker exists")
    public void aSubmittedReportExists() {
        seedSubmittedWorkerReport();
    }

    @When("the foreman opens the work reporting tab")
    public void foremanOpensTab() {
        loginUi(foreman);
        openWorkReportingTab();
    }

    @When("the worker opens the work reporting tab")
    public void workerOpensTab() {
        loginUi(worker);
        openWorkReportingTab();
    }

    @When("the worker switches to a mobile viewport")
    public void workerSwitchesToMobileViewport() {
        // Reflow to a narrow mobile viewport while the matrix dialog is already open, so the draft
        // panel collapses into its mobile reveal + drawer without the mobile nav overlay (which only
        // appears on a mobile-width page load) intercepting the panel interactions.
        page().setViewportSize(390, 844);
        matrix().draftReveal().first().waitFor();
    }

    @Then("the work reporting panel is rendered")
    public void panelRendered() {
        assertThat(tab().isRendered()).as("work-reporting-panel visible").isTrue();
    }

    @Then("the workspace URL ends with work reporting")
    public void urlEndsWithWorkReporting() {
        assertThat(page().url()).as("URL on workReporting tab").contains("/workReporting");
    }

    @Then("the new report action is visible")
    public void newReportVisible() {
        assertThat(tab().isNewReportVisible()).as("new-report action visible").isTrue();
    }

    // ---- Matrix surface steps (Amendment A1) ----

    @When("the foreman opens the matrix")
    public void foremanOpensMatrix() {
        tab().clickNewReport();
        matrix().waitOpen();
    }

    @When("the worker opens the matrix")
    public void workerOpensMatrix() {
        tab().clickNewReport();
        matrix().waitOpen();
    }

    @Then("the matrix dialog is open")
    public void matrixDialogOpen() {
        assertThat(matrix().isOpen()).as("work-report-matrix visible").isTrue();
    }

    @When("the foreman picks the worker in the matrix")
    public void foremanPicksWorker() {
        matrix().pickWorker(worker.id());
    }

    @Then("the matrix grid is rendered")
    public void matrixGridRendered() {
        matrix().waitGrid();
        assertThat(matrix().isGridVisible()).as("work-report-matrix-grid visible").isTrue();
    }

    @Then("the matrix has no worker picker")
    public void matrixNoWorkerPicker() {
        assertThat(matrix().hasWorkerPicker())
                .as("a WORKER caller loads self with no worker picker").isFalse();
    }

    @When("the worker opens the cell for room A")
    public void workerOpensCellA() {
        matrix().openCell(workItem.workItemId(), estimate.roomWithPlanAId());
        matrix().waitCellPopup();
    }

    @Then("the cell popup is open")
    public void cellPopupOpen() {
        assertThat(matrix().cellPopup().first().isVisible()).as("cell popup visible").isTrue();
    }

    @When("the worker enters a percent of {int} in the cell")
    public void workerEntersPercent(int percent) {
        matrix().cellModePercent();
        matrix().cellQuantity(String.valueOf(percent));
    }

    @Then("the cell popup shows the resolved preview")
    public void cellPopupShowsResolved() {
        matrix().cellResolvedPreview().first().waitFor();
        assertThat(matrix().cellResolvedPreview().first().isVisible())
                .as("resolved preview visible").isTrue();
    }

    @When("the worker confirms the cell entry")
    public void workerConfirmsCell() {
        matrix().cellConfirm();
        matrix().waitCellPopupClosed();
    }

    @Then("the cell for room A is staged")
    public void cellAStaged() {
        assertThat(matrix().isCellStaged(workItem.workItemId(), estimate.roomWithPlanAId()))
                .as("cell (workItem, roomA) staged").isTrue();
    }

    // ---- Draft panel steps ----

    private void stageCell(long roomId, String quantity) {
        matrix().openCell(workItem.workItemId(), roomId);
        matrix().waitCellPopup();
        matrix().cellModeAbsolute();
        matrix().cellQuantity(quantity);
        matrix().cellConfirm();
        matrix().waitCellPopupClosed();
    }

    @When("the worker stages a cell for room A with {int} absolute")
    public void stageRoomA(int qty) {
        stageCell(estimate.roomWithPlanAId(), String.valueOf(qty));
    }

    @When("the worker stages a cell for room B with {int} absolute")
    public void stageRoomB(int qty) {
        stageCell(estimate.roomWithPlanBId(), String.valueOf(qty));
    }

    @When("the worker stages the cell for room A again with {int} absolute")
    public void stageRoomAAgain(int qty) {
        stageCell(estimate.roomWithPlanAId(), String.valueOf(qty));
    }

    @Then("the draft has {int} lines")
    public void draftHasLines(int n) {
        assertThat(matrix().draftLineCount()).as("draft line count").isEqualTo(n);
    }

    @When("the worker removes the draft line for room B")
    public void removeDraftLineB() {
        matrix().draftRemove(workItem.workItemId(), estimate.roomWithPlanBId());
    }

    @When("the worker saves the draft")
    public void workerSavesDraft() {
        matrix().clickDraftSave();
        // On success the draft clears; wait for the empty state to appear.
        matrix().draftEmpty().first().waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE));
    }

    @Then("the draft is empty after save")
    public void draftEmptyAfterSave() {
        assertThat(matrix().draftLineCount()).as("draft cleared after bulk save").isEqualTo(0);
    }

    @Then("the mobile draft reveal is visible")
    public void mobileRevealVisible() {
        matrix().draftReveal().first().waitFor();
        assertThat(matrix().draftReveal().first().isVisible())
                .as("mobile draft reveal visible").isTrue();
    }

    @When("the worker opens the mobile draft drawer")
    public void openMobileDrawer() {
        matrix().clickDraftReveal();
        matrix().waitDraftSheet();
    }

    @Then("the mobile draft drawer is open")
    public void mobileDrawerOpen() {
        assertThat(matrix().draftSheet().first().isVisible())
                .as("mobile draft sheet visible").isTrue();
    }

    // ---- Review / scope steps (unchanged list + review panel) ----

    @Then("the foreman sees a review action on the submitted report")
    public void foremanSeesReview() {
        assertThat(tab().hasAnyReviewAction()).as("review action present").isTrue();
    }

    @When("the foreman opens the review and approves")
    public void foremanApprovesViaUi() {
        tab().reviewButtons().first().click();
        tab().waitReviewOpen();
        tab().approveButton().first().click();
        page().waitForTimeout(1000);
    }

    @Then("the report is approved in the list")
    public void reportApprovedInList() {
        assertThat(tab().panel().first().isVisible())
                .as("panel still rendered after approve").isTrue();
    }

    @When("the foreman opens the review and rejects without a reason")
    public void foremanRejectsNoReason() {
        tab().reviewButtons().first().click();
        tab().waitReviewOpen();
        tab().rejectButton().first().click();
        tab().reasonConfirm().first().click();
        page().waitForTimeout(500);
    }

    @Then("the review reason is required")
    public void reviewReasonRequired() {
        assertThat(tab().reasonField().first().isVisible())
                .as("reason field shown as required").isTrue();
        assertThat(tab().reviewDialog().first().isVisible())
                .as("review dialog still open (submission blocked)").isTrue();
    }

    @Then("the worker sees only their own reports and no review actions")
    public void workerOwnReportsNoReview() {
        assertThat(tab().hasAnyReviewAction()).as("no review action for a WORKER").isFalse();
    }
}
